package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.annotation.GlobalInterceptor;
import com.easychat.entity.dto.UserLoginDTO;
import com.easychat.entity.dto.UserRegisterDTO;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.vo.Result;
import com.easychat.entity.vo.SysSettingVO;
import com.easychat.entity.vo.UserInfoVO;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisComponet;
import com.easychat.redis.RedisUtils;
import com.easychat.service.UserContactService;
import com.easychat.service.UserInfoService;
import com.easychat.utils.CopyTools;
import com.easychat.websocket.MessageHandler;
import com.wf.captcha.ArithmeticCaptcha;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotEmpty;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 账户相关 Controller - 验证码、注册、登录、系统设置
 *
 * 重构记录：
 * - 新增 checkCode（原 checkCode）
 * - 注册/登录接口使用 DTO（UserRegisterDTO / UserLoginDTO）替代散参数
 * - 响应格式从 ResponseVO 迁移到 Result<T>
 */
@RestController
@RequestMapping("/account")
@Validated
public class AccountController extends ABaseController {

    @Resource
    private UserInfoService userInfoService;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private UserContactService userContactService;

    @Resource
    private RedisComponet redisComponet;

    /**
     * 获取验证码
     */
    @GetMapping(value = "/checkCode")
    public Result<Map<String, String>> checkCode() {
        ArithmeticCaptcha captcha = new ArithmeticCaptcha(100, 42);
        String code = captcha.text();
        String checkCodeKey = UUID.randomUUID().toString();
        redisUtils.setex(Constants.REDIS_KEY_CHECK_CODE + checkCodeKey, code, 60 * 10);
        String checkCodeBase64 = captcha.toBase64();
        Map<String, String> result = new HashMap<>();
        result.put("checkCode", checkCodeBase64);
        result.put("checkCodeKey", checkCodeKey);
        return success(result);
    }

    /**
     * 用户注册
     */
    @PostMapping(value = "/register")
    public Result<Void> register(UserRegisterDTO dto) {
        try {
            validateCheckCode(dto.getCheckCodeKey(), dto.getCheckCode());
            userInfoService.register(dto.getEmail(), dto.getNickName(), dto.getPassword());
            return success();
        } finally {
            redisUtils.delete(Constants.REDIS_KEY_CHECK_CODE + dto.getCheckCodeKey());
        }
    }

    /**
     * 用户登录
     */
    @PostMapping(value = "/login")
    public Result<UserInfoVO> login(UserLoginDTO dto) {
        try {
            validateCheckCode(dto.getCheckCodeKey(), dto.getCheckCode());
            UserInfoVO userInfoVO = userInfoService.login(dto.getEmail(), dto.getPassword());
            return success(userInfoVO);
        } finally {
            redisUtils.delete(Constants.REDIS_KEY_CHECK_CODE + dto.getCheckCodeKey());
        }
    }

    /**
     * 获取系统设置
     */
    @GetMapping(value = "/getSysSetting")
    @GlobalInterceptor
    public Result<SysSettingVO> getSysSetting() {
        SysSettingDto sysSettingDto = redisComponet.getSysSetting();
        return success(CopyTools.copy(sysSettingDto, SysSettingVO.class));
    }

    /**
     * 验证验证码
     */
    private void validateCheckCode(String checkCodeKey, String checkCode) {
        String cachedCode = (String) redisUtils.get(Constants.REDIS_KEY_CHECK_CODE + checkCodeKey);
        if (cachedCode == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "验证码已过期");
        }
        if (!checkCode.equalsIgnoreCase(cachedCode)) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "验证码不正确");
        }
    }
}
