package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.Result;
import com.easychat.entity.vo.ResponseVO;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * Controller 基类 - 提供统一的响应封装和通用工具方法
 */
public class ABaseController {

    protected static final String COOKIE_KEY_TOKEN = "token";

    /**
     * 旧版兼容 - 保留 status 字段，新代码使用 Result
     */
    protected static final String STATUC_SUCCESS = "success";
    protected static final String STATUC_ERROR = "error";

    @Resource
    private RedisUtils redisUtils;

    // ======================== 新版统一响应（推荐使用） ========================

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

    // ======================== 旧版兼容响应（逐步废弃） ========================

    /**
     * @deprecated 使用 {@link #success(Object)} 替代
     */
    @Deprecated
    protected <T> ResponseVO getSuccessResponseVO(T t) {
        ResponseVO<T> responseVO = new ResponseVO<>();
        responseVO.setStatus(STATUC_SUCCESS);
        responseVO.setCode(com.easychat.entity.enums.ResponseCodeEnum.CODE_200.getCode());
        responseVO.setInfo(com.easychat.entity.enums.ResponseCodeEnum.CODE_200.getMsg());
        responseVO.setData(t);
        return responseVO;
    }

    /**
     * @deprecated 使用 Result.fail(codeEnum) 替代
     */
    @Deprecated
    protected <T> ResponseVO getBusinessErrorResponseVO(BusinessException e, T t) {
        ResponseVO vo = new ResponseVO();
        vo.setStatus(STATUC_ERROR);
        if (e.getCode() == null) {
            vo.setCode(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode());
        } else {
            vo.setCode(e.getCode());
        }
        vo.setInfo(e.getMessage());
        vo.setData(t);
        return vo;
    }

    /**
     * @deprecated 使用 Result.fail() 替代
     */
    @Deprecated
    protected <T> ResponseVO getServerErrorResponseVO(T t) {
        ResponseVO vo = new ResponseVO();
        vo.setStatus(STATUC_ERROR);
        vo.setCode(com.easychat.entity.enums.ResponseCodeEnum.CODE_1002.getCode());
        vo.setInfo(com.easychat.entity.enums.ResponseCodeEnum.CODE_1002.getMsg());
        vo.setData(t);
        return vo;
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
