package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.MomentCommentVO;
import com.easychat.entity.vo.MomentLikeResultVO;
import com.easychat.entity.vo.MomentVO;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.service.MomentService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 朋友圈接口
 */
@RestController
@RequestMapping("/moment")
public class MomentController extends ABaseController {

    @Resource
    private MomentService momentService;

    @RequestMapping("/publish")
    @GlobalInterceptor
    public ResponseVO publish(HttpServletRequest request,
                              @NotEmpty String content,
                              Integer visibility,
                              String visibleList,
                              String invisibleList,
                              String location) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        MomentVO vo = momentService.publish(content, visibility, visibleList, invisibleList, location, userInfoDto);
        return getSuccessResponseVO(vo);
    }

    @RequestMapping("/list")
    @GlobalInterceptor
    public ResponseVO list(HttpServletRequest request, Integer pageNo, Integer pageSize) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        List<MomentVO> list = momentService.loadMomentList(userInfoDto, pageNo, pageSize);
        return getSuccessResponseVO(list);
    }

    @RequestMapping("/like")
    @GlobalInterceptor
    public ResponseVO like(HttpServletRequest request,
                           @NotNull Long momentId,
                           Boolean cancel) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        MomentLikeResultVO resultVO = momentService.likeOrCancel(momentId, cancel != null && cancel, userInfoDto);
        return getSuccessResponseVO(resultVO);
    }

    @RequestMapping("/comment")
    @GlobalInterceptor
    public ResponseVO comment(HttpServletRequest request,
                              @NotNull Long momentId,
                              @NotEmpty String content,
                              Long parentId,
                              String replyToUserId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        MomentCommentVO vo = momentService.addComment(momentId, content, parentId, replyToUserId, userInfoDto);
        return getSuccessResponseVO(vo);
    }


    @RequestMapping("/uploadMedia")
    @GlobalInterceptor
    public ResponseVO uploadMedia(HttpServletRequest request,
                                  @RequestParam("momentId") @NotNull Long momentId,
                                  @RequestParam("file") @NotNull MultipartFile file,
                                  @RequestParam(value = "mediaType", required = false) Integer mediaType) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        String filePath = momentService.uploadMedia(momentId, file, mediaType, userInfoDto);
        return getSuccessResponseVO(filePath);
    }

    /**
     * 上传朋友圈媒体文件分片
     */
    @RequestMapping("/uploadMediaChunk")
    @GlobalInterceptor
    public ResponseVO uploadMediaChunk(HttpServletRequest request,
                                       @NotEmpty String fileId,
                                       @NotNull Integer chunkIndex,
                                       @NotNull Integer totalChunks,
                                       @NotNull MultipartFile chunk) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentService.uploadMediaChunk(fileId, chunkIndex, totalChunks, chunk, userInfoDto);
        return getSuccessResponseVO(null);
    }

    /**
     * 合并朋友圈媒体文件分片
     */
    @RequestMapping("/mergeMediaChunks")
    @GlobalInterceptor
    public ResponseVO mergeMediaChunks(HttpServletRequest request,
                                       @NotEmpty String fileId,
                                       @NotNull Long momentId,
                                       @NotEmpty String fileName,
                                       @NotNull Integer totalChunks,
                                       @NotNull Integer mediaType) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        String filePath = momentService.mergeMediaChunks(fileId, momentId, fileName, totalChunks, mediaType, userInfoDto);
        return getSuccessResponseVO(filePath);
    }

    /**
     * 检查朋友圈媒体已上传的分片
     */
    @RequestMapping("/checkMediaChunks")
    @GlobalInterceptor
    public ResponseVO checkMediaChunks(HttpServletRequest request,
                                       @NotEmpty String fileId,
                                       @NotNull Integer totalChunks) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return getSuccessResponseVO(momentService.checkMediaChunks(fileId, totalChunks, userInfoDto));
    }

    @RequestMapping("/delete")
    @GlobalInterceptor
    public ResponseVO delete(HttpServletRequest request,
                             @NotNull Long momentId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentService.deleteMoment(momentId, userInfoDto);
        return getSuccessResponseVO(null);
    }


}


