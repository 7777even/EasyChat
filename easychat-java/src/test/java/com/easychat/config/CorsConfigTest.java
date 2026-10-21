package com.easychat.config;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 会话，即构成 CSRF 暴露面。
 *
 * <p><b>2026-10-21（L4，人工已确认）</b>：允许来源改为<b>可配置</b>
 * （{@code easychat.cors.allowed-origin-patterns}，<b>默认仍为 {@code *}</b> → 行为未变），
 * 且「凭据 + 通配来源」并存时<b>启动即告警</b>。本类的既有用例<b>锁定的正是「默认行为」</b> ——
 * 它们全程保持绿，就是「默认未变」的直接证据。
 *
 * <p>⚠ <b>本类使用 JUnit 5</b>：断言参数序是 <b>(条件/实际值, 消息)</b>，
 * 与 JUnit 4 的 <b>(消息, 条件/实际值)</b> <b>相反</b>。本仓同时存在 JUnit 4 与 JUnit 5
 * （`junit-vintage-engine` + `spring-boot-starter-test`），
 * 写反了<b>能编译但语义相反</b>（消息被当成待断言的值，报错信息极具迷惑性）。请注意。
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

    // ── 2026-10-21（L4，人工已确认）：允许来源可配置 + 宽松组合告警 ──────────

    /**
     * 允许来源**可配置**，且白名单外的来源**不被回显**（否则收紧形同虚设）。
     *
     * <p>默认行为不变（{@code *}）：上面的既有用例已锁定「回显任意来源」。
     */
    @Test
    @DisplayName("允许来源可配置：白名单内回显、白名单外不回显")
    void allowedOriginsAreConfigurable() throws Exception {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns",
                "https://a.example.com, https://b.example.com");
        FilterRegistrationBean<CorsFilter> reg = config.corsFilterRegistration();

        MockHttpServletRequest allowed = new MockHttpServletRequest("GET", "/api/chat/loadMessage");
        allowed.addHeader("Origin", "https://a.example.com");
        MockHttpServletResponse allowedRes = new MockHttpServletResponse();
        reg.getFilter().doFilter(allowed, allowedRes, new MockFilterChain());
        assertEquals("https://a.example.com", allowedRes.getHeader("Access-Control-Allow-Origin"),
                "白名单内的来源应被回显");

        MockHttpServletRequest denied = new MockHttpServletRequest("GET", "/api/chat/loadMessage");
        denied.addHeader("Origin", "https://evil.example.com");
        MockHttpServletResponse deniedRes = new MockHttpServletResponse();
        reg.getFilter().doFilter(denied, deniedRes, new MockFilterChain());
        assertNull(deniedRes.getHeader("Access-Control-Allow-Origin"),
                "不在白名单的来源不得被回显 —— 否则「收紧」只是摆设");
    }

    @Test
    @DisplayName("纯判定：含通配符的来源列表 = 宽松（与凭据组合即 CSRF 暴露面）")
    void wildcardDetectionIsPureLogic() {
        assertTrue(CorsConfig.isWildcardOrigin(CorsConfig.parseOriginPatterns("*")),
                "通配来源必须被识别为宽松");
        assertTrue(CorsConfig.isWildcardOrigin(CorsConfig.parseOriginPatterns("https://a.com, http://*")),
                "列表中含前缀通配也算宽松");
        assertFalse(CorsConfig.isWildcardOrigin(CorsConfig.parseOriginPatterns("https://a.com, https://b.com")),
                "精确白名单不应被判为宽松");
    }

    @Test
    @DisplayName("宽松组合必须给出可操作告警（含收紧用的配置键）；白名单则不告警")
    void permissiveCombinationProducesActionableWarning() {
        String warn = CorsConfig.permissiveWarning(CorsConfig.parseOriginPatterns("*"));
        assertNotNull(CorsConfig.permissiveWarning(CorsConfig.parseOriginPatterns("*")),
                "通配来源 + 凭据 必须告警（否则这仍是隐性雷）");
        assertTrue(warn.contains("easychat.cors.allowed-origin-patterns"),
                "告警须指出收紧用的配置键");
        assertTrue(warn.contains("Cookie"),
                "告警须说明风险条件（改用 Cookie 会话即构成 CSRF 暴露面）");
        assertNull(CorsConfig.permissiveWarning(CorsConfig.parseOriginPatterns("https://a.com")),
                "精确白名单（无通配）不应告警");
    }

    @Test
    @DisplayName("空配置回退到通配（误配不应把应用打死），但仍被判为宽松")
    void emptyConfigFallsBackToWildcard() {
        assertEquals(Collections.singletonList("*"), CorsConfig.parseOriginPatterns(""));
        assertEquals(Collections.singletonList("*"), CorsConfig.parseOriginPatterns("  ,  "));
        assertTrue(CorsConfig.isWildcardOrigin(CorsConfig.parseOriginPatterns("")),
                "回退后的通配仍属宽松，须告警");
    }
}
