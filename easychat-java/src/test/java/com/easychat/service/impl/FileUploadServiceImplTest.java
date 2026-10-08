package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FileUploadServiceImpl 单元测试。
 *
 * <p>大文件分片上传是纯文件 I/O 面：分片落临时目录
 * （/file/temp/{userId}/{fileId}/{i}.chunk）、合并前
 * 逐片存在性校验、合并内容 = 分片按序拼接、封面与
 * 目标文件同目录、合并后无论成败都清理临时目录。
 * 用真实临时目录验证落盘内容，防「路径拼错静默写
 * 飞」。此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class FileUploadServiceImplTest {

    private static final String USER = "U_me";
    private static final String FILE_ID = "F001";
    private static final Long MESSAGE_ID = 5555L;

    @InjectMocks
    private FileUploadServiceImpl fileUploadService;

    @Mock
    private AppConfig appConfig;

    private TokenUserInfoDto tokenOf(String userId) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(userId);
        return dto;
    }

    private String tempProjectFolder() throws Exception {
        String tempDir = Files.createTempDirectory("file-upload-test").toString();
        when(appConfig.getProjectFolder()).thenReturn(tempDir);
        return tempDir;
    }

    private File chunkFolder(String tempDir) {
        return new File(tempDir + Constants.FILE_FOLDER_FILE + "temp/"
                + USER + "/" + FILE_ID);
    }

    private void writeChunk(String tempDir, int index, String content) throws Exception {
        File folder = chunkFolder(tempDir);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("测试前置：创建分片目录失败");
        }
        File chunk = new File(folder, index + ".chunk");
        Files.write(chunk.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    // ======================== uploadChunk ========================

    @Test
    public void uploadChunk_persistsChunkFile() throws Exception {
        String tempDir = tempProjectFolder();
        MockMultipartFile chunk = new MockMultipartFile("chunk", "0.chunk",
                "application/octet-stream", "分片内容".getBytes(StandardCharsets.UTF_8));

        fileUploadService.uploadChunk(FILE_ID, 0, 3, chunk, tokenOf(USER));

        File stored = new File(chunkFolder(tempDir), "0.chunk");
        assertTrue("分片应落盘到 " + stored, stored.exists());
        assertEquals("分片内容",
                new String(Files.readAllBytes(stored.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void uploadChunk_transferFailure_throws1002() throws Exception {
        tempProjectFolder();
        MultipartFile chunk = mock(MultipartFile.class);
        doThrow(new RuntimeException("disk full"))
                .when(chunk).transferTo(any(File.class));

        try {
            fileUploadService.uploadChunk(FILE_ID, 0, 3, chunk, tokenOf(USER));
            fail("分片写入失败应抛 1002");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1002.getCode(), e.getCode());
        }
    }

    // ======================== mergeChunks ========================

    @Test
    public void mergeChunks_missingChunk_throwsAndCleansTemp() throws Exception {
        String tempDir = tempProjectFolder();
        writeChunk(tempDir, 0, "AB");
        // 只上传了 0 号分片，合并 2 片 → 1 号缺失
        try {
            fileUploadService.mergeChunks(FILE_ID, MESSAGE_ID, "a.mp4",
                    2, null, tokenOf(USER));
            fail("分片缺失应拒绝合并");
        } catch (BusinessException e) {
            assertTrue("异常信息应指明缺失分片，实际: " + e.getMessage(),
                    e.getMessage().contains("分片1不存在"));
        }
        // finally 清理临时分片目录
        assertFalse("临时分片目录应被清理", chunkFolder(tempDir).exists());
    }

    @Test
    public void mergeChunks_success_mergesInOrderAndSavesCover() throws Exception {
        String tempDir = tempProjectFolder();
        writeChunk(tempDir, 0, "AB");
        writeChunk(tempDir, 1, "CD");
        MockMultipartFile cover = new MockMultipartFile("cover", "cover.png",
                "image/png", "封面".getBytes(StandardCharsets.UTF_8));

        fileUploadService.mergeChunks(FILE_ID, MESSAGE_ID, "a.mp4",
                2, cover, tokenOf(USER));

        // 合并内容 = 分片按序拼接
        File target = new File(tempDir + Constants.FILE_FOLDER_FILE
                + MESSAGE_ID + ".mp4");
        assertTrue("合并文件应落盘到 " + target, target.exists());
        assertEquals("ABCD",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        // 封面与目标文件同目录
        File coverFile = new File(target.getPath() + Constants.COVER_IMAGE_SUFFIX);
        assertTrue("封面应落盘到 " + coverFile, coverFile.exists());
        // 临时分片目录已清理
        assertFalse("临时分片目录应被清理", chunkFolder(tempDir).exists());
        target.delete();
        coverFile.delete();
    }

    // ======================== checkUploadedChunks ========================

    @Test
    public void checkUploadedChunks_returnsExistingIndices() throws Exception {
        String tempDir = tempProjectFolder();
        writeChunk(tempDir, 0, "A");
        writeChunk(tempDir, 2, "C");

        List<Integer> uploaded =
                fileUploadService.checkUploadedChunks(FILE_ID, 3, tokenOf(USER));

        assertEquals(List.of(0, 2), uploaded);
    }

    @Test
    public void checkUploadedChunks_noChunks_returnsEmpty() throws Exception {
        tempProjectFolder();
        List<Integer> uploaded =
                fileUploadService.checkUploadedChunks(FILE_ID, 3, tokenOf(USER));
        assertTrue("未上传任何分片应返回空列表", uploaded.isEmpty());
    }

    // ======================== mergeGroupFile ========================

    @Test
    public void mergeGroupFile_success_returnsStoredNameAndCleansTemp() throws Exception {
        String tempDir = tempProjectFolder();
        writeChunk(tempDir, 0, "XY");
        writeChunk(tempDir, 1, "ZW");

        String storedName = fileUploadService.mergeGroupFile(FILE_ID, "g.bin",
                2, tokenOf(USER));

        // 群文件以 fileId 命名，落群文件目录
        assertEquals("F001.bin", storedName);
        File target = new File(tempDir + Constants.FILE_FOLDER_FILE
                + Constants.FILE_FOLDER_GROUP + "F001.bin");
        assertTrue("群文件应落盘到 " + target, target.exists());
        assertEquals("XYZW",
                new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertFalse("临时分片目录应被清理", chunkFolder(tempDir).exists());
        target.delete();
    }

    @Test
    public void mergeGroupFile_missingChunk_throws() throws Exception {
        String tempDir = tempProjectFolder();
        writeChunk(tempDir, 0, "XY");
        try {
            fileUploadService.mergeGroupFile(FILE_ID, "g.bin",
                    2, tokenOf(USER));
            fail("分片缺失应拒绝合并");
        } catch (BusinessException e) {
            assertTrue("异常信息应指明缺失分片，实际: " + e.getMessage(),
                    e.getMessage().contains("分片1不存在"));
        }
    }
}
