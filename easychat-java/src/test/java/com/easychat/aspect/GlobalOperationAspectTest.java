package com.easychat.aspect;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.controller.AccountController;
import com.easychat.controller.AdminUserInfoController;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.dto.UserLoginDTO;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalOperationAspect} 的鉴权与限流单测。
 *
 * <p><b>为什么这些必须有单测</b>：本切面是 AGENTS §8 列明的 L4 面（认证鉴权 + 横切），
 * 此前全仓<b>零</b>单测。2026-10-03 盘点实测出它的 {@code checkRateLimit} 在
 * {@code token == null} 时直接 {@code return}，而登录页没有 token 头，
 * 导致 {@code login} / {@code register} / {@code sendEmailCode} / {@code resetPassword}
 * 四个 {@code checkLogin = false} 端点的限流<b>从未生效</b>——不抛异常、不打警告，
 * 常规冒烟与 Service 层单测（mock 掉 Redis）都照不到。
 *
 * <p><b>手法</b>：不用 {@code @SpringBootTest}，直接构造切面并把
 * {@link RequestContextHolder} 指向 {@link MockHttpServletRequest}；
 * {@link MethodSignature} 返回<b>真实 Controller 方法</b>，使注解是生产代码里那一份，
 * 从而同时验证「注解标注是否正确」与「切面行为是否正确」——两者任一漂移本测试都会转红。
 *
 * @since 2026-10-03 密码变更后会话失效（openspec/specs/password-bcrypt）
 */
@RunWith(MockitoJUnitRunner.class)
public class GlobalOperationAspectTest {

    private static final String TOKEN = "tok_valid";
    private static final String USER_ID = "U_admin";
    /** 限流阈值，与 {@code GlobalOperationAspect#checkRateLimit} 中的常量一致 */
    private static final int RATE_LIMIT = 60;

    private GlobalOperationAspect aspect;

    @Mock
    private RedisUtils redisUtils;

    private MockHttpServletRequest request;

    @Before
    public void setUp() {
        aspect = new GlobalOperationAspect();
        // 切面用 @Resource 注入 RedisUtils，单测里直接注入到私有字段
        org.springframework.test.util.ReflectionTestUtils.setField(aspect, "redisUtils", redisUtils);

        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ==================== 工具 ====================

    /** 用真实 Controller 方法构造 JoinPoint，使切面读到生产代码里的注解 */
    private JoinPoint joinPointOf(Method method) {
        MethodSignature signature = org.mockito.Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint point = org.mockito.Mockito.mock(JoinPoint.class);
        when(point.getSignature()).thenReturn(signature);
        return point;
    }

    private JoinPoint loginEndpoint() throws Exception {
        return joinPointOf(AccountController.class.getMethod("login", UserLoginDTO.class));
    }

    private JoinPoint resetPasswordEndpoint() throws Exception {
        return joinPointOf(
                AccountController.class.getMethod("resetPassword", String.class, String.class, String.class));
    }

    private JoinPoint getSysSettingEndpoint() throws Exception {
        return joinPointOf(AccountController.class.getMethod("getSysSetting"));
    }

    private JoinPoint adminLoadUserEndpoint() throws Exception {
        return joinPointOf(AdminUserInfoController.class.getMethod("loadUser", UserInfoQuery.class));
    }

    private void tokenHeaderPresent(boolean admin) {
        request.addHeader("token", TOKEN);
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(USER_ID);
        dto.setAdmin(admin);
        when(redisUtils.get("easychat:ws:token:" + TOKEN)).thenReturn(dto);
    }

    private void expectCode(BusinessException e, int expected) {
        assertEquals("业务错误码不符，期望 " + expected + " 实际 " + e.getCode(), Integer.valueOf(expected), e.getCode());
    }

    private BusinessException catchBusiness(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e;
        }
        fail("期望抛 BusinessException，但没有抛出");
        return null;
    }

    // ==================== checkLogin ====================

    @Test
    public void checkLogin_noToken_throws2001() throws Exception {
        // 无 token 头 → Redis 查不到会话
        when(redisUtils.get(anyString())).thenReturn(null);
        // 端点解析必须在 lambda 外：Class#getMethod 抛受检异常，lambda 不允许
        JoinPoint point = getSysSettingEndpoint();
        BusinessException e = catchBusiness(() -> aspect.interceptorDo(point));
        expectCode(e, 2001);
    }

    @Test
    public void checkLogin_validToken_passes() throws Exception {
        tokenHeaderPresent(false);
        aspect.interceptorDo(getSysSettingEndpoint());
        verify(redisUtils, times(1)).get("easychat:ws:token:" + TOKEN);
    }

    @Test
    public void interceptor_annotationAbsent_isNoop() throws Exception {
        // 未标注 @GlobalInterceptor 的方法应直接放行，连 Redis 都不碰
        Method plain = String.class.getMethod("length");
        JoinPoint point = joinPointOf(plain);
        aspect.interceptorDo(point);
        verifyNoInteractions(redisUtils);
    }

    // ==================== checkAdmin ====================

    @Test
    public void checkAdmin_nonAdminToken_throws1003() throws Exception {
        tokenHeaderPresent(false);
        JoinPoint point = adminLoadUserEndpoint();
        BusinessException e = catchBusiness(() -> aspect.interceptorDo(point));
        expectCode(e, 1003);
    }

    @Test
    public void checkAdmin_adminToken_passes() throws Exception {
        tokenHeaderPresent(true);
        aspect.interceptorDo(adminLoadUserEndpoint());
        verify(redisUtils, times(1)).get("easychat:ws:token:" + TOKEN);
    }

    @Test
    public void checkAdmin_noToken_throws2001_not1003() throws Exception {
        // 未认证应报 2001（先判会话），而不是 1003（越权）——顺序不能反
        when(redisUtils.get(anyString())).thenReturn(null);
        JoinPoint point = adminLoadUserEndpoint();
        BusinessException e = catchBusiness(() -> aspect.interceptorDo(point));
        expectCode(e, 2001);
    }

    // ==================== checkRateLimit（本次修复主体） ====================

    @Test
    public void rateLimit_noTokenHeader_doesNotEarlyReturn() throws Exception {
        // 回归：原实现是 `if (token == null) return;` → 未登录端点限流完全失效。
        // 无 token 头时也必须走限流（按 IP 维度），而不是静默放行。
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn(1L);

        aspect.interceptorDo(loginEndpoint());

        verify(redisUtils, times(1)).incr(anyString());
        verify(redisUtils, never()).incr(eq("rate_limit:"));
    }

    @Test
    public void rateLimit_noTokenHeader_setsWindowOnFirstRequest() throws Exception {
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn(1L);

        aspect.interceptorDo(loginEndpoint());

        verify(redisUtils, times(1)).expire(anyString(), eq(60L));
    }

    @Test
    public void rateLimit_overThreshold_throws1001() throws Exception {
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn((long) RATE_LIMIT + 1);

        JoinPoint point = loginEndpoint();
        BusinessException e = catchBusiness(() -> aspect.interceptorDo(point));
        expectCode(e, 1001);
    }

    @Test
    public void rateLimit_atThreshold_passes() throws Exception {
        // 恰好等于阈值应放行（判据是 > 60）
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn((long) RATE_LIMIT);

        aspect.interceptorDo(loginEndpoint());
    }

    @Test
    public void rateLimit_withToken_usesTokenDimensionKey() throws Exception {
        // 回归保护：token 维度分支不能被改掉。
        // 注意：全仓 checkRateLimit=true 的 4 个端点都是 checkLogin=false，
        // 该分支只能靠「客户端带着旧 token 重新登录」触达（如重登、改密后重登）。
        // 因此这里必须用 loginEndpoint + token 头，而不是用只标 @GlobalInterceptor 的端点。
        tokenHeaderPresent(false);
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn(1L);

        aspect.interceptorDo(loginEndpoint());

        verify(redisUtils, times(1)).incr("rate_limit:" + TOKEN);
    }

    @Test
    public void rateLimit_loginEndpoint_overThreshold_throws1001() throws Exception {
        // 直接对 /account/login 这个真实端点验证限流真的生效（annotation + 切面联合）
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn((long) RATE_LIMIT + 1);

        JoinPoint point = loginEndpoint();
        BusinessException e = catchBusiness(() -> aspect.interceptorDo(point));
        expectCode(e, 1001);
    }

    @Test
    public void rateLimit_resetPasswordEndpoint_overThreshold_throws1001() throws Exception {
        // /account/resetPassword 是本批的核心受害端点（验证码可暴力），必须确认它受限
        request.setRemoteAddr("10.0.0.7");
        when(redisUtils.incr(anyString())).thenReturn((long) RATE_LIMIT + 1);

        JoinPoint point = resetPasswordEndpoint();
        BusinessException e = catchBusiness(() -> aspect.interceptorDo(point));
        expectCode(e, 1001);
    }
}