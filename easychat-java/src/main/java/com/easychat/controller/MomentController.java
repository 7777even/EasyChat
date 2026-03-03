package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.MomentCommentVO;
import com.easychat.entity.vo.MomentLikeResultVO;
import com.easychat.entity.vo.MomentVO;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.service.MomentService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}


