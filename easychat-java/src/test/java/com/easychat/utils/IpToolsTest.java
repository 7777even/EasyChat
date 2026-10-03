package com.easychat.utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.Assert.assertEquals;

/**
 * {@link IpTools} 单元测试。
 *
 * <p><b>为什么这些必须有单测</b>：本类的取值规则同时被两处消费——
 * {@code GlobalOperationAspect#checkRateLimit}（限流维度）与
 * {@code OperationLogServiceImpl#recordLog}（审计溯源）。
 * 一旦规则写错，<b>限流会误伤正常用户</b>（全部流量归到同一个 key）或
 * <b>审计日志会集体写成同一个假 IP</b>，两者都<b>不抛异常</b>。
 *
 * <p>尤其要守住「无请求上下文」这条：{@code recordLog} 也可能被启动任务/异步线程调用，
 * 此时 {@code RequestContextHolder.getRequestAttributes()} 返回 null，
 * 若不兜住就会 NPE——而 {@code recordLog} 外层的 try-catch 会把它吞掉，
 * 表现为<b>审计日志静默丢失</b>，比抛异常更难发现。
 *
 * @since 2026-10-03（openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）
 */
public class IpToolsTest {

    private MockHttpServletRequest request;

    @Before
    public void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ==================== 代理场景 ====================

    @Test
    public void getClientIp_xffSingleValue() {
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertEquals("203.0.113.7", IpTools.getClientIp());
    }

    @Test
    public void getClientIp_xffMultiHop_takesFirstSegment() {
        // 多跳：客户端 → 代理1 → 代理2。首段才是最初发起请求的客户端
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.1, 198.51.100.2");
        assertEquals("203.0.113.7", IpTools.getClientIp());
    }

    @Test
    public void getClientIp_xffWithSpaces_trimmed() {
        // "  203.0.113.7 , 198.51.100.1" —— 首段两侧空白必须裁掉，否则 key 里会带空格
        request.addHeader("X-Forwarded-For", "  203.0.113.7 , 198.51.100.1");
        assertEquals("203.0.113.7", IpTools.getClientIp());
    }

    @Test
    public void getClientIp_emptyXff_fallsBackToRemoteAddr() {
        request.addHeader("X-Forwarded-For", "");
        request.setRemoteAddr("192.0.2.10");
        assertEquals("192.0.2.10", IpTools.getClientIp());
    }

    @Test
    public void getClientIp_blankXff_fallsBackToRemoteAddr() {
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("192.0.2.10");
        assertEquals("192.0.2.10", IpTools.getClientIp());
    }

    @Test
    public void getClientIp_leadingCommaFallsBackToRemoteAddr() {
        // 病态输入 ",," —— 首段切出来是空串，必须回退而不是返回空 IP
        request.addHeader("X-Forwarded-For", ",,");
        request.setRemoteAddr("192.0.2.10");
        assertEquals("192.0.2.10", IpTools.getClientIp());
    }

    // ==================== 直连场景 ====================

    @Test
    public void getClientIp_noXff_usesRemoteAddr() {
        request.setRemoteAddr("192.0.2.10");
        assertEquals("192.0.2.10", IpTools.getClientIp());
    }

    // ==================== 兜底 ====================

    @Test
    public void getClientIp_nothingAvailable_returnsPlaceholder() {
        // 既无 XFF 也无 remoteAddr。返回占位值而非 null/空串：
        // 空串会让限流 key 退化成 "rate_limit:ip:" 这种全体共享的 key（等于限流失效）。
        // ⚠ MockHttpServletRequest 的 remoteAddr 默认值就是 127.0.0.1，
        //   必须显式置空才能真正走到「两者皆无」这条分支。
        request.setRemoteAddr(null);
        assertEquals("-", IpTools.getClientIp());
    }

    @Test
    public void getClientIp_noRequestContext_returnsPlaceholderWithoutThrowing() {
        // 非 HTTP 线程（启动任务、@Async）：requestAttributes 为 null。
        // 这条最关键：不兜住就是 NPE，而 recordLog 的外层 catch 会把它吞成「日志静默丢失」。
        RequestContextHolder.resetRequestAttributes();
        assertEquals("-", IpTools.getClientIp());
    }
}