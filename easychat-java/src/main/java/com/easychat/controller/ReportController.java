package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.controller.ABaseController;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.Result;
import com.easychat.service.ReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;

@RestController
@RequestMapping("/report")
public class ReportController extends ABaseController {

    @Resource
    private ReportService reportService;

    /**
     * 举报朋友圈动态或评论（momentId 与 commentId 二选一）
     */
    @PostMapping("/moment")
    @GlobalInterceptor
    public Result<Void> reportMoment(HttpServletRequest request,
                                     Long momentId,
                                     Long commentId,
                                     @NotNull Integer reason,
                                     String description) {
        TokenUserInfoDto user = getTokenUserInfo(request);
        reportService.reportMoment(momentId, commentId, reason, description, user);
        return success();
    }

    /**
     * 举报聊天消息
     */
    @PostMapping("/chat")
    @GlobalInterceptor
    public Result<Void> reportChat(HttpServletRequest request,
                                   @NotNull Long messageId,
                                   @NotNull Integer reason,
                                   String description) {
        TokenUserInfoDto user = getTokenUserInfo(request);
        reportService.reportMessage(messageId, reason, description, user);
        return success();
    }
}
