package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.Result;
import com.easychat.redis.RedisUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * Controller 基类 - 提供统一的响应封装和通用工具方法
 */
public class ABaseController {

    protected static final String COOKIE_KEY_TOKEN = "token";

    @Resource
    private RedisUtils redisUtils;

    // ======================== 统一响应（Result<T>） ========================
    // 旧包络 ResponseVO 及其三个兼容方法已随契约统一移除，禁止再引入第二套响应外壳。

    /**
     * 成功响应（带数据）
     */
    protected <T> Result<T> success(T data) {
        return Result.success(data);
    }

    /**
     * 成功响应（无数据）
     */
    protected <T> Result<T> success() {
        return Result.success();
    }

    // ======================== 通用工具方法 ========================

    /**
     * 从请求头获取当前登录用户信息
     */
    protected TokenUserInfoDto getTokenUserInfo(HttpServletRequest request) {
        String token = request.getHeader("token");
        return (TokenUserInfoDto) redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + token);
    }

    /**
     * 刷新当前登录用户的会话信息
     */
    protected void resetTokenUserInfo(HttpServletRequest request, TokenUserInfoDto tokenUserInfoDto) {
        String token = request.getHeader("token");
        redisUtils.setex(Constants.REDIS_KEY_WS_TOKEN + token, tokenUserInfoDto, Constants.REDIS_KEY_EXPIRES_DAY * 2);
    }
}
