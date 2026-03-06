package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.service.FileUploadService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

/**
 * 分片上传控制器
 */
@RestController
@RequestMapping("/upload")
public class FileUploadController extends ABaseController {

    @Resource
    private FileUploadService fileUploadService;

    /**
     * 上传文件分片
     */
    @RequestMapping("/uploadChunk")
    @GlobalInterceptor
    public ResponseVO uploadChunk(HttpServletRequest request,
                                  @NotEmpty String fileId,
                                  @NotNull Integer chunkIndex,
                                  @NotNull Integer totalChunks,
                                  @NotNull MultipartFile chunk) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        fileUploadService.uploadChunk(fileId, chunkIndex, totalChunks, chunk, userInfoDto);
        return getSuccessResponseVO(null);
    }

    /**
     * 合并文件分片
     */
    @RequestMapping("/mergeChunks")
    @GlobalInterceptor
    public ResponseVO mergeChunks(HttpServletRequest request,
                                  @NotEmpty String fileId,
                                  @NotNull Long messageId,
                                  @NotEmpty String fileName,
                                  @NotNull Integer totalChunks,
                                  MultipartFile cover) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        fileUploadService.mergeChunks(fileId, messageId, fileName, totalChunks, cover, userInfoDto);
        return getSuccessResponseVO(null);
    }

    /**
     * 检查已上传的分片
     */
    @RequestMapping("/checkChunks")
    @GlobalInterceptor
    public ResponseVO checkChunks(HttpServletRequest request,
                                  @NotEmpty String fileId,
                                  @NotNull Integer totalChunks) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return getSuccessResponseVO(fileUploadService.checkUploadedChunks(fileId, totalChunks, userInfoDto));
    }
}
