package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.Result;
import com.easychat.service.FileUploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

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
    @PostMapping("/uploadChunk")
    @GlobalInterceptor
    public Result<Void> uploadChunk(HttpServletRequest request,
                                  @NotEmpty String fileId,
                                  @NotNull Integer chunkIndex,
                                  @NotNull Integer totalChunks,
                                  @NotNull MultipartFile chunk) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        fileUploadService.uploadChunk(fileId, chunkIndex, totalChunks, chunk, userInfoDto);
        return success(null);
    }

    /**
     * 合并文件分片
     */
    @PostMapping("/mergeChunks")
    @GlobalInterceptor
    public Result<Void> mergeChunks(HttpServletRequest request,
                                  @NotEmpty String fileId,
                                  @NotNull Long messageId,
                                  @NotEmpty String fileName,
                                  @NotNull Integer totalChunks,
                                  MultipartFile cover) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        fileUploadService.mergeChunks(fileId, messageId, fileName, totalChunks, cover, userInfoDto);
        return success(null);
    }

    /**
     * 检查已上传的分片
     */
    @PostMapping("/checkChunks")
    @GlobalInterceptor
    public Result<List<Integer>> checkChunks(HttpServletRequest request,
                                  @NotEmpty String fileId,
                                  @NotNull Integer totalChunks) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(fileUploadService.checkUploadedChunks(fileId, totalChunks, userInfoDto));
    }
}
