package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.vo.Result;
import com.easychat.entity.vo.UserInfoVO;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisComponet;
import com.easychat.redis.RedisUtils;
import com.easychat.service.UserContactService;
import com.easychat.service.UserInfoService;
import com.easychat.websocket.MessageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.validation.Validation;
import javax.validation.Validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AccountController} 的**行为**测试（MockMvc 真实路由 + 真实全局异常处理器）。
 *
 * <p>背景：此前 23 个 Controller **全部零行为测试**——鉴权注解矩阵有反射式结构测试兜着，
 * 但「路由 / 统一包络 / 参数校验 / 异常码透传 / 前置守卫」这段（AGENTS §3.1~§3.2 的契约主体）
 * 无人验证。这类缺陷的表现是**不抛异常、只在客户端表现为提示不对或流程走不通**。
 *
 * <p>被测点：
 * <ol>
 *   <li>路由与 HTTP 方法：登录/注册/发码/改密为 POST，取图形验证码为 GET（§3.5 写操作不得用 GET）</li>
 *   <li>统一包络：成功一律 {@code code=0}，失败一律带业务码（§3.1 禁止第二套响应外壳）</li>
 *   <li>验证码前置守卫：过期 → 1001「验证码已过期」；不匹配 → 1001「验证码不正确」；大小写不敏感</li>
 *   <li><b>finally 语义</b>：无论成功或抛异常都必须删掉验证码 key（换码重发即依赖此点，删掉则码可无限重试）</li>
 *   <li>业务异常透传：service 抛的码与文案原样出现在响应里，不被 Controller 吞掉或改写</li>
 *   <li>守卫短路：验证码不过时<b>不得</b>调用 service（否则等于绕过图形验证码）</li>
 *   <li>DTO 自身的 Bean Validation 约束确实生效（用 Validator 直接校验 DTO，见类尾说明）</li>
 * </ol>
 *
 * <p>⚠ 口径说明：MockMvc {@code standaloneSetup} <b>不会</b>注册
 * {@code MethodValidationPostProcessor}，故方法参数上的 {@code @NotEmpty String email}
 * 这类约束在本测试中不会被强制执行——这是测试环境限制而非产品行为，故不对其做断言，
 * 避免写出「因环境缺件而恒绿」的假覆盖（AGENTS §2.1 第 3 条）。DTO 字段级约束改用
 * {@link Validation} 直接校验对象断言。
 */
class AccountControllerMockMvcTest {

    private static final String CODE_KEY = "abc123";

    @Mock
    private UserInfoService userInfoService;
    @Mock
    private RedisUtils redisUtils;
    @Mock
    private MessageHandler messageHandler;
    @Mock
    private UserContactService userContactService;
    @Mock
    private RedisComponet redisComponet;

    @InjectMocks
    private AccountController controller;

    private MockMvc mockMvc;

    private final AGlobalExceptionHandlerController exceptionHandler = new AGlobalExceptionHandlerController();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    // ==================== 路由与 HTTP 方法（§3.5） ====================

    @Test
    @DisplayName("写操作不得用 GET：GET /account/login 应 405")
    void loginRejectsGet() throws Exception {
        mockMvc.perform(get("/account/login"))
                .andExpect(status().isMethodNotAllowed());
        verify(userInfoService, never()).login(anyString(), anyString());
    }

    @Test
    @DisplayName("取图形验证码是幂等查询 → GET /account/checkCode 返回 200 + code=0")
    void checkCodeIsGetAndSucceeds() throws Exception {
        mockMvc.perform(get("/account/checkCode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.checkCode").isNotEmpty())
                .andExpect(jsonPath("$.data.checkCodeKey").isNotEmpty());

        // 验证码入缓存 10 分钟（60 * 10）
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redisUtils).setex(key.capture(), anyString(), eq(600L));
        assertTrue(key.getValue().startsWith(Constants.REDIS_KEY_CHECK_CODE));
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("登录成功 → code=0 且 data 为登录用户；验证码 key 被删除")
    void loginSucceeds() throws Exception {
        givenCachedCode("ab12");
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId("U001");
        vo.setNickName("小明");
        when(userInfoService.login("a@b.com", "pwd12345")).thenReturn(vo);

        mockMvc.perform(post("/account/login")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("checkCode", "AB12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("U001"));

        verify(redisUtils).delete(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY);
    }

    @Test
    @DisplayName("验证码大小写不敏感（equalsIgnoreCase）")
    void checkCodeIsCaseInsensitive() throws Exception {
        givenCachedCode("AB12");
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId("U001");
        when(userInfoService.login(anyString(), anyString())).thenReturn(vo);

        mockMvc.perform(post("/account/login")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("checkCode", "ab12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("验证码过期（缓存无此 key）→ 400 + 1001「验证码已过期」，且不得调用 login")
    void expiredCheckCodeIsRejected() throws Exception {
        when(redisUtils.get(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY)).thenReturn(null);

        mockMvc.perform(post("/account/login")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("checkCode", "ab12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("验证码已过期"));

        verify(userInfoService, never()).login(anyString(), anyString());
        // 过期也必须删 key，否则同一 key 可被反复试探
        verify(redisUtils).delete(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY);
    }

    @Test
    @DisplayName("验证码不匹配 → 400 + 1001「验证码不正确」，且不得调用 login")
    void wrongCheckCodeIsRejected() throws Exception {
        givenCachedCode("9999");

        mockMvc.perform(post("/account/login")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("checkCode", "ab12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("验证码不正确"));

        verify(userInfoService, never()).login(anyString(), anyString());
        verify(redisUtils).delete(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY);
    }

    @Test
    @DisplayName("业务异常原样透传：service 抛的码与文案不被 Controller 改写")
    void businessExceptionPropagatesUnchanged() throws Exception {
        givenCachedCode("ab12");
        when(userInfoService.login(anyString(), anyString()))
                .thenThrow(new BusinessException("账号密码错误"));

        mockMvc.perform(post("/account/login")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "bad")
                        .param("checkCode", "ab12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("账号密码错误"));

        // 异常路径同样要删 key
        verify(redisUtils).delete(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY);
    }

    @Test
    @DisplayName("未捕获异常兜底为 500 + 1002（不得把内部错误当成功或裸 500 页面）")
    void unexpectedExceptionBecomes500() throws Exception {
        givenCachedCode("ab12");
        when(userInfoService.login(anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/account/login")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("checkCode", "ab12"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(1002));
    }

    // ==================== 注册 ====================

    @Test
    @DisplayName("注册成功 → 调用 register(email, nickName, password) 且 code=0")
    void registerSucceeds() throws Exception {
        givenCachedCode("ab12");

        mockMvc.perform(post("/account/register")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("nickName", "小明")
                        .param("checkCode", "ab12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userInfoService).register("a@b.com", "小明", "pwd12345");
        verify(redisUtils).delete(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY);
    }

    @Test
    @DisplayName("注册时验证码不过 → 绝不调用 register（否则等于绕过图形验证码）")
    void registerShortCircuitsBeforeService() throws Exception {
        when(redisUtils.get(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY)).thenReturn(null);

        mockMvc.perform(post("/account/register")
                        .param("checkCodeKey", CODE_KEY)
                        .param("email", "a@b.com")
                        .param("password", "pwd12345")
                        .param("nickName", "小明")
                        .param("checkCode", "ab12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        verify(userInfoService, never()).register(anyString(), anyString(), anyString());
    }

    // ==================== 邮箱验证码 / 找回密码 ====================

    @Test
    @DisplayName("发送邮箱验证码：type 缺省按 0（注册）处理")
    void sendEmailCodeDefaultsToRegister() throws Exception {
        mockMvc.perform(post("/account/sendEmailCode").param("email", "a@b.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userInfoService).sendEmailCode("a@b.com", 0);
    }

    @Test
    @DisplayName("发送邮箱验证码：type=1 找回密码")
    void sendEmailCodeForReset() throws Exception {
        mockMvc.perform(post("/account/sendEmailCode")
                        .param("email", "a@b.com")
                        .param("type", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userInfoService).sendEmailCode("a@b.com", 1);
    }

    @Test
    @DisplayName("重置密码 → 透传 email / code / newPassword 三参数")
    void resetPasswordDelegates() throws Exception {
        mockMvc.perform(post("/account/resetPassword")
                        .param("email", "a@b.com")
                        .param("code", "123456")
                        .param("newPassword", "newpwd12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userInfoService).resetPasswordByEmail("a@b.com", "123456", "newpwd12");
    }

    @Test
    @DisplayName("重置密码失败 → 异常码透传（不得被 Controller 吞成成功）")
    void resetPasswordFailurePropagates() throws Exception {
        // void 方法不能用 when(...)（when 要求有返回值），必须走 doThrow().when(...)
        doThrow(new BusinessException("验证码错误或已过期"))
                .when(userInfoService).resetPasswordByEmail(anyString(), anyString(), anyString());

        mockMvc.perform(post("/account/resetPassword")
                        .param("email", "a@b.com")
                        .param("code", "000000")
                        .param("newPassword", "newpwd12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("验证码错误或已过期"));
    }

    // ==================== 系统设置 ====================

    @Test
    @DisplayName("getSysSetting → 复制为 VO 返回（不回传 DTO）")
    void getSysSettingReturnsVo() throws Exception {
        SysSettingDto dto = new SysSettingDto();
        dto.setMaxImageSize(9);
        when(redisComponet.getSysSetting()).thenReturn(dto);

        mockMvc.perform(get("/account/getSysSetting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.maxImageSize").value(9))
                // VO 只暴露前端需要的三个上限字段，其余（机器人 UID / 昵称 / 欢迎语）不得外泄
                .andExpect(jsonPath("$.data.robotUid").doesNotExist());
    }

    // ==================== DTO 字段级约束 ====================

    @Test
    @DisplayName("UserLoginDTO 自身的 Bean Validation 约束生效（空邮箱 / 非法邮箱被拒）")
    void loginDtoConstraintsAreEffective() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        com.easychat.entity.dto.UserLoginDTO bad = new com.easychat.entity.dto.UserLoginDTO();
        assertFalse(validator.validate(bad).isEmpty(), "空 DTO 应校验失败");

        com.easychat.entity.dto.UserLoginDTO badEmail = new com.easychat.entity.dto.UserLoginDTO();
        badEmail.setCheckCodeKey("k");
        badEmail.setEmail("not-an-email");
        badEmail.setPassword("pwd12345");
        badEmail.setCheckCode("1234");
        assertFalse(validator.validate(badEmail).isEmpty(), "非法邮箱应校验失败");

        com.easychat.entity.dto.UserLoginDTO good = new com.easychat.entity.dto.UserLoginDTO();
        good.setCheckCodeKey("k");
        good.setEmail("a@b.com");
        good.setPassword("pwd12345");
        good.setCheckCode("1234");
        assertTrue(validator.validate(good).isEmpty(), "合法值应校验通过");
    }

    private void givenCachedCode(String code) {
        when(redisUtils.get(Constants.REDIS_KEY_CHECK_CODE + CODE_KEY)).thenReturn(code);
    }
}
