package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.UserStatus;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import com.easychat.service.UserStatusService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

@RestController
@RequestMapping("/userStatus")
public class UserStatusController extends ABaseController {

    @Resource
    private UserStatusService userStatusService;

    /**
     * 设置状态
     */
    @PostMapping("/set")
    @GlobalInterceptor
    public Result<Void> setStatus(HttpServletRequest request,
                                  @NotEmpty @Size(max = 500) String content,
                                  String imageUrl) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userStatusService.setStatus(tokenUserInfoDto.getUserId(), content, imageUrl);
        return success();
    }

    /**
     * 获取状态
     */
    @GetMapping("/get")
    @GlobalInterceptor
    public Result<UserStatus> getStatus(HttpServletRequest request, @NotEmpty String userId) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserStatus userStatus = userStatusService.getStatus(userId);
        return success(userStatus);
    }

    /**
     * 清除状态
     */
    @PostMapping("/clear")
    @GlobalInterceptor
    public Result<Void> clearStatus(HttpServletRequest request) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userStatusService.clearStatus(tokenUserInfoDto.getUserId());
        return success();
    }
}
