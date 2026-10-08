package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.po.Emoji;
import com.easychat.entity.query.EmojiQuery;
import com.easychat.entity.vo.EmojiVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.EmojiMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmojiServiceImpl 单元测试。
 *
 * <p>表情包上传是文件写路径：空文件 / 超限（5MB）/ 非图片
 * 后缀三重校验、落盘目录（projectFolder + /file/emoji/）与
 * 数据库记录一致、删除时「记录归属当前用户」校验 + 文件清理。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class EmojiServiceImplTest {

    private static final String USER = "U_me";
    private static final Long EMOJI_ID = 8001L;
    private static final int MAX_SIZE = 5 * 1024 * 1024;

    @InjectMocks
    private EmojiServiceImpl emojiService;

    @Mock
    private EmojiMapper<Emoji, EmojiQuery> emojiMapper;

    @Mock
    private AppConfig appConfig;

    private Emoji emojiOf(Long id, String fileName, String filePath) {
        Emoji emoji = new Emoji();
        emoji.setId(id);
        emoji.setUserId(USER);
        emoji.setFileName(fileName);
        emoji.setFilePath(filePath);
        emoji.setFileSize(1024L);
        emoji.setEmojiType(1);
        emoji.setCreateTime(System.currentTimeMillis());
        return emoji;
    }

    private MultipartFile mockFile(long size, String originalFilename) {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        when(file.getOriginalFilename()).thenReturn(originalFilename);
        return file;
    }

    // ======================== listEmoji ========================

    @Test
    public void listEmoji_nullList_returnsEmpty() {
        when(emojiMapper.selectList(any(EmojiQuery.class))).thenReturn(null);
        List<EmojiVO> result = emojiService.listEmoji(USER);
        assertTrue("null 列表应回落为空列表", result.isEmpty());
    }

    @Test
    public void listEmoji_success_returnsVOs() {
        when(emojiMapper.selectList(any(EmojiQuery.class)))
                .thenReturn(Arrays.asList(
                        emojiOf(EMOJI_ID, "表情.png", "1000.png"),
                        emojiOf(EMOJI_ID + 1, "表情2.gif", "1001.gif")));
        List<EmojiVO> result = emojiService.listEmoji(USER);
        assertEquals(2, result.size());
        assertEquals("表情.png", result.get(0).getFileName());
        assertEquals("1000.png", result.get(0).getFilePath());
        assertEquals(Long.valueOf(1024L), result.get(0).getFileSize());
    }

    // ======================== uploadEmoji：校验 ========================

    @Test
    public void uploadEmoji_nullFile_throwsWithMessage() {
        try {
            emojiService.uploadEmoji(USER, null);
            fail("空文件应拒绝");
        } catch (BusinessException e) {
            assertEquals("文件不能为空", e.getMessage());
        }
    }

    @Test
    public void uploadEmoji_emptyFile_throwsWithMessage() {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);
        try {
            emojiService.uploadEmoji(USER, file);
            fail("空文件应拒绝");
        } catch (BusinessException e) {
            assertEquals("文件不能为空", e.getMessage());
        }
    }

    @Test
    public void uploadEmoji_oversize_throwsWithMessage() {
        MultipartFile file = mockFile(MAX_SIZE + 1L, "表情.png");
        try {
            emojiService.uploadEmoji(USER, file);
            fail("超限文件应拒绝");
        } catch (BusinessException e) {
            assertEquals("文件大小超过限制，最大 5MB", e.getMessage());
        }
    }

    @Test
    public void uploadEmoji_exactlyMaxSize_passesSizeCheck() {
        // 边界：恰好 5MB 允许通过大小校验
        MultipartFile file = mockFile(MAX_SIZE, "边界.png");
        when(appConfig.getProjectFolder()).thenReturn(System.getProperty("java.io.tmpdir"));
        emojiService.uploadEmoji(USER, file);
        verify(emojiMapper).insert(any(Emoji.class));
    }

    @Test
    public void uploadEmoji_emptyFileName_throwsWithMessage() {
        MultipartFile file = mockFile(100L, null);
        try {
            emojiService.uploadEmoji(USER, file);
            fail("文件名为空应拒绝");
        } catch (BusinessException e) {
            assertEquals("文件名不能为空", e.getMessage());
        }
    }

    @Test
    public void uploadEmoji_nonImageSuffix_throwsWithMessage() {
        MultipartFile file = mockFile(100L, "表情.txt");
        try {
            emojiService.uploadEmoji(USER, file);
            fail("非图片格式应拒绝");
        } catch (BusinessException e) {
            assertEquals("仅支持图片格式", e.getMessage());
        }
    }

    // ======================== uploadEmoji：落盘与落库 ========================

    @Test
    public void uploadEmoji_success_persistsFileAndRecord() throws Exception {
        String tempDir = Files.createTempDirectory("emoji-upload-test").toString();
        when(appConfig.getProjectFolder()).thenReturn(tempDir);

        byte[] content = "fake-png-bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "表情.png",
                "image/png", content);

        EmojiVO result = emojiService.uploadEmoji(USER, file);

        // 数据库记录：原始文件名 + 时间戳新文件名 + 大小 + 类型 1 + 服务端创建时间
        ArgumentCaptor<Emoji> captor = ArgumentCaptor.forClass(Emoji.class);
        verify(emojiMapper).insert(captor.capture());
        Emoji inserted = captor.getValue();
        assertEquals(USER, inserted.getUserId());
        assertEquals("表情.png", inserted.getFileName());
        assertTrue("存储文件名应为 时间戳.png，实际: " + inserted.getFilePath(),
                inserted.getFilePath().matches("\\d+\\.png"));
        assertEquals(Long.valueOf(content.length), inserted.getFileSize());
        assertEquals(Integer.valueOf(1), inserted.getEmojiType());
        assertNotNull("创建时间由服务端填充", inserted.getCreateTime());

        // 文件实际落盘：projectFolder + /file/emoji/<存储文件名>
        File stored = new File(tempDir + Constants.FILE_FOLDER_FILE + "emoji/"
                + inserted.getFilePath());
        assertTrue("文件应落盘到 " + stored, stored.exists());
        // 返回 VO 与记录一致
        assertEquals("表情.png", result.getFileName());
        assertEquals(inserted.getFilePath(), result.getFilePath());

        // 清理临时文件
        assertTrue(stored.delete());
    }

    // ======================== deleteEmoji ========================

    @Test
    public void deleteEmoji_nullId_throwsWithMessage() {
        try {
            emojiService.deleteEmoji(USER, null);
            fail("表情包ID为空应拒绝");
        } catch (BusinessException e) {
            assertEquals("表情包ID不能为空", e.getMessage());
        }
    }

    @Test
    public void deleteEmoji_notOwned_throwsWithMessage() {
        // 列表中只有别人的表情包：归属校验不通过
        when(emojiMapper.selectList(any(EmojiQuery.class)))
                .thenReturn(Collections.singletonList(emojiOf(EMOJI_ID + 5, "别人的.png", "x.png")));
        try {
            emojiService.deleteEmoji(USER, EMOJI_ID);
            fail("删除不存在的表情包应拒绝");
        } catch (BusinessException e) {
            assertEquals("表情包不存在", e.getMessage());
        }
        verify(emojiMapper, never()).deleteById(any(Long.class));
    }

    @Test
    public void deleteEmoji_success_deletesRecordAndFile() throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        when(appConfig.getProjectFolder()).thenReturn(tempDir);
        // 磁盘上存在对应文件
        File stored = new File(tempDir + Constants.FILE_FOLDER_FILE + "emoji/8001.png");
        try {
            assertTrue("测试前置：创建临时表情文件失败", stored.createNewFile());
        } finally {
            stored.deleteOnExit();
        }
        when(emojiMapper.selectList(any(EmojiQuery.class)))
                .thenReturn(Collections.singletonList(emojiOf(EMOJI_ID, "表情.png", "8001.png")));

        emojiService.deleteEmoji(USER, EMOJI_ID);

        verify(emojiMapper).deleteById(EMOJI_ID);
        assertTrue("落盘文件应一并删除", !stored.exists());
    }
}
