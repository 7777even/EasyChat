package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件分片上传服务
 */
public interface FileUploadService {

    /**
     * 上传文件分片
     */
    void uploadChunk(String fileId, Integer chunkIndex, Integer totalChunks, 
                     MultipartFile chunk, TokenUserInfoDto userInfoDto);

    /**
     * 合并文件分片
     */
    void mergeChunks(String fileId, Long messageId, String fileName, 
                     Integer totalChunks, MultipartFile cover, TokenUserInfoDto userInfoDto);

    /**
     * 检查已上传的分片
     */
    List<Integer> checkUploadedChunks(String fileId, Integer totalChunks, TokenUserInfoDto userInfoDto);

    /**
     * 合并群文件分片：从临时目录合并到 file/group/，返回存储文件名（{fileId}.{ext}）
     */
    String mergeGroupFile(String fileId, String fileName, Integer totalChunks, TokenUserInfoDto userInfoDto);
}
