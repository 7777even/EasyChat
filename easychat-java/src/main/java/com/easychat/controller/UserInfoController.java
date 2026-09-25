package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.dto.UserUpdateDTO;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.vo.Result;
import com.easychat.entity.vo.UserInfoVO;
import com.easychat.exception.BusinessException;
import com.easychat.service.UserInfoService;
import com.easychat.utils.CopyTools;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import java.io.IOException;

/**
 * 用户信息管理 Controller - 获取/更新用户信息、修改密码、退出登录
 *
 * 重构记录：
 * - 响应格式从 ResponseVO 迁移到 Result<T>
 * - 更新用户信息使用 UserUpdateDTO 替代直接接收 Entity
 * - 新增 /getUserInfo 显式映射 userId
 */
@RestController
@RequestMapping("/userInfo")
public class UserInfoController extends ABaseController {

    @Resource
    private UserInfoService userInfoService;

    @Resource
    private ChannelContextUtils channelContextUtils;

    /**
     * 获取当前用户信息
     */
    @PostMapping("/getUserInfo")
    @GlobalInterceptor
    public Result<UserInfoVO> getUserInfo(HttpServletRequest request) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserInfo userInfo = userInfoService.getUserInfoByUserId(tokenUserInfoDto.getUserId());
        if (userInfo == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2101);
        }
        UserInfoVO userInfoVO = CopyTools.copy(userInfo, UserInfoVO.class);
        userInfoVO.setAdmin(tokenUserInfoDto.getAdmin());
        return success(userInfoVO);
    }

    /**
     * 保存/更新用户信息
     */
    @PostMapping("/saveUserInfo")
    @GlobalInterceptor
    public Result<UserInfoVO> saveUserInfo(HttpServletRequest request, UserUpdateDTO dto) throws IOException {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        UserInfo userInfo = CopyTools.copy(dto, UserInfo.class);
        userInfo.setUserId(tokenUserInfoDto.getUserId());
        userInfo.setPassword(null);
        userInfo.setStatus(null);
        userInfo.setCreateTime(null);
        userInfo.setLastLoginTime(null);
        this.userInfoService.updateUserInfo(userInfo, dto.getAvatarFile(), dto.getAvatarCover());
        if (dto.getNickName() != null && !tokenUserInfoDto.getNickName().equals(dto.getNickName())) {
            tokenUserInfoDto.setNickName(dto.getNickName());
            resetTokenUserInfo(request, tokenUserInfoDto);
        }
        return getUserInfo(request);
    }

    /**
     * 修改密码
     * <p>
     * 安全修复：必须校验旧密码。历史实现只接收新密码，
     * 一旦 token 泄漏即可直接改密接管账号。
     */
    @PostMapping("/updatePassword")
    @GlobalInterceptor
    public Result<Void> updatePassword(HttpServletRequest request,
                                       @NotEmpty(message = "原密码不能为空") String oldPassword,
                                       @NotEmpty(message = "新密码不能为空")
                                       @Pattern(regexp = Constants.REGEX_PASSWORD, message = "密码格式不正确")
                                       String password) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        userInfoService.updatePassword(tokenUserInfoDto.getUserId(), oldPassword, password);
        // 关闭 WebSocket 连接，强制重新登录
        channelContextUtils.closeContext(tokenUserInfoDto.getUserId());
        return success();
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    @GlobalInterceptor
    public Result<Void> logout(HttpServletRequest request) {
        TokenUserInfoDto tokenUserInfoDto = getTokenUserInfo(request);
        // 关闭 WebSocket 连接
        channelContextUtils.closeContext(tokenUserInfoDto.getUserId());
        return success();
    }
}
