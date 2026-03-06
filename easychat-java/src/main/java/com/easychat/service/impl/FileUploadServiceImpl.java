package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import com.easychat.service.FileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件分片上传服务实现
 */
@Service
public class FileUploadServiceImpl implements FileUploadService {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadServiceImpl.class);

    @Resource
    private AppConfig appConfig;

    /**
     * 获取分片临时目录
     */
    private File getChunkTempFolder(String fileId, String userId) {
        String tempPath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + "temp/" + userId + "/" + fileId;
        File folder = new File(tempPath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    @Override
    public void uploadChunk(String fileId, Integer chunkIndex, Integer totalChunks,
                            MultipartFile chunk, TokenUserInfoDto userInfoDto) {
        try {
            File chunkFolder = getChunkTempFolder(fileId, userInfoDto.getUserId());
            File chunkFile = new File(chunkFolder, chunkIndex + ".chunk");
            
            // 保存分片
            chunk.transferTo(chunkFile);
            
            logger.info("分片上传成功: fileId={}, chunkIndex={}/{}, size={}", 
                    fileId, chunkIndex, totalChunks, chunk.getSize());
        } catch (Exception e) {
            logger.error("分片上传失败", e);
            throw new BusinessException(ResponseCodeEnum.CODE_500);
        }
    }

    @Override
    public void mergeChunks(String fileId, Long messageId, String fileName,
                            Integer totalChunks, MultipartFile cover, TokenUserInfoDto userInfoDto) {
        File chunkFolder = getChunkTempFolder(fileId, userInfoDto.getUserId());
        
        try {
            // 验证所有分片是否存在
            for (int i = 0; i < totalChunks; i++) {
                File chunkFile = new File(chunkFolder, i + ".chunk");
                if (!chunkFile.exists()) {
                    throw new BusinessException("分片" + i + "不存在，无法合并");
                }
            }

            // 创建目标文件目录
            String fileExtName = fileName.substring(fileName.lastIndexOf("."));
            String fileRealName = messageId + fileExtName;
            File targetFolder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }
            
            File targetFile = new File(targetFolder, fileRealName);
            
            // 合并分片
            try (FileOutputStream fos = new FileOutputStream(targetFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                
                for (int i = 0; i < totalChunks; i++) {
                    File chunkFile = new File(chunkFolder, i + ".chunk");
                    try (FileInputStream fis = new FileInputStream(chunkFile);
                         BufferedInputStream bis = new BufferedInputStream(fis)) {
                        
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = bis.read(buffer)) != -1) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                bos.flush();
            }

            // 保存封面
            if (cover != null && !cover.isEmpty()) {
                File coverFile = new File(targetFile.getPath() + Constants.COVER_IMAGE_SUFFIX);
                cover.transferTo(coverFile);
            }

            logger.info("文件合并成功: fileId={}, messageId={}, targetFile={}", 
                    fileId, messageId, targetFile.getAbsolutePath());

        } catch (Exception e) {
            logger.error("文件合并失败", e);
            throw new BusinessException("文件合并失败: " + e.getMessage());
        } finally {
            // 清理临时分片文件
            deleteChunkFolder(chunkFolder);
        }
    }

    @Override
    public List<Integer> checkUploadedChunks(String fileId, Integer totalChunks, TokenUserInfoDto userInfoDto) {
        List<Integer> uploadedChunks = new ArrayList<>();
        File chunkFolder = getChunkTempFolder(fileId, userInfoDto.getUserId());
        
        if (!chunkFolder.exists()) {
            return uploadedChunks;
        }

        for (int i = 0; i < totalChunks; i++) {
            File chunkFile = new File(chunkFolder, i + ".chunk");
            if (chunkFile.exists()) {
                uploadedChunks.add(i);
            }
        }

        logger.info("检查已上传分片: fileId={}, uploaded={}/{}", fileId, uploadedChunks.size(), totalChunks);
        return uploadedChunks;
    }

    /**
     * 删除分片临时文件夹
     */
    private void deleteChunkFolder(File folder) {
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            folder.delete();
            logger.info("清理临时分片文件夹: {}", folder.getAbsolutePath());
        }
    }
}
