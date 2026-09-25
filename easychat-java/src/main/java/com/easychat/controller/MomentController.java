package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.MomentCommentVO;
import com.easychat.entity.vo.MomentLikeResultVO;
import com.easychat.entity.vo.MomentNotifyVO;
import com.easychat.entity.vo.MomentVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.MomentNotifyService;
import com.easychat.service.MomentService;
import org.springframework.web.bind.annotation.PostMapping;
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
 * 朋友圈接口（含通知中心）
 */
@RestController
@RequestMapping("/moment")
public class MomentController extends ABaseController {

    @Resource
    private MomentService momentService;

    @Resource
    private MomentNotifyService momentNotifyService;

    @PostMapping("/publish")
    @GlobalInterceptor
    public Result<MomentVO> publish(HttpServletRequest request,
                                    @NotEmpty String content,
                                    Integer visibility,
                                    String visibleList,
                                    String invisibleList,
                                    String location) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        MomentVO vo = momentService.publish(content, visibility, visibleList, invisibleList, location, userInfoDto);
        return success(vo);
    }

    @PostMapping("/list")
    @GlobalInterceptor
    public Result<List<MomentVO>> list(HttpServletRequest request, Integer pageNo, Integer pageSize) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        List<MomentVO> list = momentService.loadMomentList(userInfoDto, pageNo, pageSize);
        return success(list);
    }

    /**
     * 动态详情
     */
    @PostMapping("/detail")
    @GlobalInterceptor
    public Result<MomentVO> detail(HttpServletRequest request, @NotNull Long momentId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(momentService.loadMomentDetail(momentId, userInfoDto));
    }

    /**
     * 朋友圈个人主页：某用户发布的动态
     */
    @PostMapping("/userMomentList")
    @GlobalInterceptor
    public Result<List<MomentVO>> userMomentList(HttpServletRequest request,
                                                 @NotEmpty String targetUserId,
                                                 Integer pageNo,
                                                 Integer pageSize) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(momentService.loadUserMomentList(targetUserId, userInfoDto, pageNo, pageSize));
    }

    @PostMapping("/like")
    @GlobalInterceptor
    public Result<MomentLikeResultVO> like(HttpServletRequest request,
                                           @NotNull Long momentId,
                                           Boolean cancel) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        MomentLikeResultVO resultVO = momentService.likeOrCancel(momentId, cancel != null && cancel, userInfoDto);
        return success(resultVO);
    }

    @PostMapping("/comment")
    @GlobalInterceptor
    public Result<MomentCommentVO> comment(HttpServletRequest request,
                                           @NotNull Long momentId,
                                           @NotEmpty String content,
                                           Long parentId,
                                           String replyToUserId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        MomentCommentVO vo = momentService.addComment(momentId, content, parentId, replyToUserId, userInfoDto);
        return success(vo);
    }

    /**
     * 删除评论（评论本人或动态作者可删）
     */
    @PostMapping("/deleteComment")
    @GlobalInterceptor
    public Result<Void> deleteComment(HttpServletRequest request, @NotNull Long commentId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentService.deleteComment(commentId, userInfoDto);
        return success();
    }

    @PostMapping("/uploadMedia")
    @GlobalInterceptor
    public Result<String> uploadMedia(HttpServletRequest request,
                                      @RequestParam("momentId") @NotNull Long momentId,
                                      @RequestParam("file") @NotNull MultipartFile file,
                                      @RequestParam(value = "mediaType", required = false) Integer mediaType) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        String filePath = momentService.uploadMedia(momentId, file, mediaType, userInfoDto);
        return success(filePath);
    }

    /**
     * 上传朋友圈媒体文件分片
     */
    @PostMapping("/uploadMediaChunk")
    @GlobalInterceptor
    public Result<Void> uploadMediaChunk(HttpServletRequest request,
                                         @NotEmpty String fileId,
                                         @NotNull Integer chunkIndex,
                                         @NotNull Integer totalChunks,
                                         @NotNull MultipartFile chunk) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentService.uploadMediaChunk(fileId, chunkIndex, totalChunks, chunk, userInfoDto);
        return success();
    }

    /**
     * 合并朋友圈媒体文件分片
     */
    @PostMapping("/mergeMediaChunks")
    @GlobalInterceptor
    public Result<String> mergeMediaChunks(HttpServletRequest request,
                                           @NotEmpty String fileId,
                                           @NotNull Long momentId,
                                           @NotEmpty String fileName,
                                           @NotNull Integer totalChunks,
                                           @NotNull Integer mediaType) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        String filePath = momentService.mergeMediaChunks(fileId, momentId, fileName, totalChunks, mediaType, userInfoDto);
        return success(filePath);
    }

    /**
     * 检查朋友圈媒体已上传的分片
     */
    @PostMapping("/checkMediaChunks")
    @GlobalInterceptor
    public Result<List<Integer>> checkMediaChunks(HttpServletRequest request,
                                                  @NotEmpty String fileId,
                                                  @NotNull Integer totalChunks) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(momentService.checkMediaChunks(fileId, totalChunks, userInfoDto));
    }

    @PostMapping("/delete")
    @GlobalInterceptor
    public Result<Void> delete(HttpServletRequest request,
                               @NotNull Long momentId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentService.deleteMoment(momentId, userInfoDto);
        return success();
    }

    /* ==================== 朋友圈通知中心 ==================== */

    /**
     * 未读通知数（朋友圈红点）
     */
    @PostMapping("/notify/unreadCount")
    @GlobalInterceptor
    public Result<Integer> unreadCount(HttpServletRequest request) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(momentNotifyService.getUnreadCount(userInfoDto.getUserId()));
    }

    /**
     * 通知列表
     */
    @PostMapping("/notify/list")
    @GlobalInterceptor
    public Result<PaginationResultVO<MomentNotifyVO>> notifyList(HttpServletRequest request,
                                                                 Integer pageNo,
                                                                 Integer pageSize) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(momentNotifyService.loadNotifyList(userInfoDto.getUserId(), pageNo, pageSize));
    }

    /**
     * 最近几条通知（toast 文案用）
     */
    @PostMapping("/notify/recent")
    @GlobalInterceptor
    public Result<List<MomentNotifyVO>> notifyRecent(HttpServletRequest request, Integer limit) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        return success(momentNotifyService.loadRecentNotify(userInfoDto.getUserId(), limit));
    }

    /**
     * 全部标记已读
     */
    @PostMapping("/notify/markAllRead")
    @GlobalInterceptor
    public Result<Void> markAllRead(HttpServletRequest request) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentNotifyService.markAllRead(userInfoDto.getUserId());
        return success();
    }

    /**
     * 按类型标记已读
     */
    @PostMapping("/notify/markReadByType")
    @GlobalInterceptor
    public Result<Void> markReadByType(HttpServletRequest request, @NotNull Integer type) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentNotifyService.markReadByType(userInfoDto.getUserId(), type);
        return success();
    }

    /**
     * 单条标记已读
     */
    @PostMapping("/notify/markRead")
    @GlobalInterceptor
    public Result<Void> markRead(HttpServletRequest request, @NotNull Long notifyId) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentNotifyService.markRead(userInfoDto.getUserId(), notifyId);
        return success();
    }

    /**
     * 清空通知
     */
    @PostMapping("/notify/clear")
    @GlobalInterceptor
    public Result<Void> clearNotify(HttpServletRequest request) {
        TokenUserInfoDto userInfoDto = getTokenUserInfo(request);
        momentNotifyService.clearNotify(userInfoDto.getUserId());
        return success();
    }
}
