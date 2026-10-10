package com.easychat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CorsConfig} 的**行为**测试（Filter 级）。
 *
 * <p>CORS 是 AGENTS §8 明列的 L4 横切配置，故此处<b>只锁定现状、不做修改</b>。
 * 锁定的目的是：一旦有人改动这些头，测试立刻报警，而不是等到浏览器侧出现「跨域失败」
 * 或「凭据被任意站点带走」时才从日志里反推。
 *
 * <p>⚠ <b>待人工决策的安全观察（非本测试判定为缺陷）</b>：
 * 当前同时开启 {@code allowCredentials(true)} 与 {@code allowedOriginPattern("*")}，
 * 意味着<b>任意第三方站点</b>都能发起携带凭据的跨域请求并读取响应。
 * 这在「token 走请求头 + 桌面端」的前提下危害有限，但若将来任何端点改用 Cookie
 * 会话，即构成 CSRF 暴露面。是否收紧属契约/安全决策，需人工确认后另行实施。
 */
class CorsConfigTest {

    private CorsFilter filter;
    private FilterRegistrationBean<CorsFilter> registration;

    @BeforeEach
    void setUp() {
        registration = new CorsConfig().corsFilterRegistration();
        filter = registration.getFilter();
    }

    @Test
    @DisplayName("注册为最高优先级过滤器（必须在 AOP 拦截器之前，否则 OPTIONS 预检先被拦）")
    void filterHasHighestPrecedence() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder(),
                "CORS 过滤器顺序被改即意味着预检请求会先经过业务拦截器");
    }

    @Test
    @DisplayName("简单请求：回显任意来源 + 允许携带凭据（当前配置现状，见类注释的风险说明）")
    void simpleRequestAllowsAnyOriginWithCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/loadMessage");
        request.addHeader("Origin", "https://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("https://evil.example.com", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    @DisplayName("预检请求：放行任意方法与请求头，并带 1 小时预检缓存")
    void preflightAllowsAnyMethodAndHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/chat/sendMessage");
        request.addHeader("Origin", "https://any.example.com");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "token,content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("https://any.example.com", response.getHeader("Access-Control-Allow-Origin"));
        assertNotNull(response.getHeader("Access-Control-Allow-Methods"));
        assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("POST"),
                "预检必须放行前端实际使用的方法");
        assertEquals("3600", response.getHeader("Access-Control-Max-Age"));
    }

    @Test
    @DisplayName("无 Origin 头的请求不被添加 CORS 头（非跨域请求不应被污染）")
    void requestWithoutOriginGetsNoCorsHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/loadMessage");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }
}
