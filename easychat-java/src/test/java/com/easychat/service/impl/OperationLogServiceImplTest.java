package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.po.OperationLog;
import com.easychat.entity.query.OperationLogQuery;
import com.easychat.mappers.OperationLogMapper;
import com.easychat.utils.IpTools;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * {@link OperationLogServiceImpl} 单元测试。
 *
 * <p><b>为什么这些必须有单测</b>：{@code operation_log.ip_address} 列早已存在、
 * 接口也带 {@code ipAddress} 参数，但此前全仓 6 处调用点无一例外传 {@code null}
 * → 审计日志存在却无法用于审计，且最需要溯源的 {@code LOGIN_FAILED}
 * （密码爆破无法定位来源 IP）、{@code FORCE_OFFLINE}、{@code UPDATE_PASSWORD} 全部没有 IP。
 *
 * <p><b>本类最容易踩的两个坑，单测各钉一条</b>：
 * <ol>
 *   <li><b>补齐时机</b>：若先 {@code setIpAddress(占位值)} 再被覆盖，或干脆在
 *       {@code setIpAddress} 之后才补齐，落库的就是空值——修复形同虚设。
 *       单测从 <b>Mapper 入参</b>侧断言，而不是只看方法体内出现过调用。</li>
 *   <li><b>显式值不被覆盖</b>：ADR-001 决定「入参为空才自动补齐」。
 *       若无脑覆盖，将来某个调用方（如内部任务）想显式标注 IP 就会失效。</li>
 * </ol>
 *
 * @since 2026-10-03（openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth）
 */
@RunWith(MockitoJUnitRunner.class)
public class OperationLogServiceImplTest {

    private static final String USER_ID = "U12345678901";
    private static final String TYPE = "LOGIN_FAILED";
    private static final String DESC = "登录失败：密码错误";

    private OperationLogServiceImpl operationLogService;

    @Mock
    private OperationLogMapper<OperationLog, OperationLogQuery> operationLogMapper;

    private MockHttpServletRequest request;

    @Before
    public void setUp() {
        operationLogService = new OperationLogServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(operationLogService, "operationLogMapper", operationLogMapper);
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private OperationLog capture() {
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 入参为空 → 自动补齐 ====================

    @Test
    public void recordLog_nullIp_fillsClientIpFromRequest() {
        request.setRemoteAddr("192.0.2.55");

        operationLogService.recordLog(USER_ID, TYPE, DESC, null);

        assertEquals("ip_address 未被自动补齐", "192.0.2.55", capture().getIpAddress());
    }

    @Test
    public void recordLog_nullIp_prefersForwardedForFirstSegment() {
        // 走代理的场景：落库的应是客户端 IP，而不是代理 IP
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.1");
        request.setRemoteAddr("198.51.100.1");

        operationLogService.recordLog(USER_ID, TYPE, DESC, null);

        assertEquals("203.0.113.7", capture().getIpAddress());
    }

    @Test
    public void recordLog_emptyIp_alsoFills() {
        // 空串与 null 同样属于「调用方没给」，应一并补齐
        request.setRemoteAddr("192.0.2.55");

        operationLogService.recordLog(USER_ID, TYPE, DESC, "");

        assertEquals("192.0.2.55", capture().getIpAddress());
    }

    // ==================== 显式传入不被覆盖 ====================

    @Test
    public void recordLog_explicitIp_isNotOverwritten() {
        request.setRemoteAddr("192.0.2.55");

        operationLogService.recordLog(USER_ID, "INTERNAL_TASK", "内部任务", "10.1.2.3");

        assertEquals("显式传入的 IP 被自动补齐覆盖了", "10.1.2.3", capture().getIpAddress());
    }

    // ==================== 无请求上下文 ====================

    @Test
    public void recordLog_noRequestContext_writesPlaceholder() {
        // 非 HTTP 线程（启动任务 / @Async）。必须落占位值而不是 null：
        // 「有值但表示未知」与「没采到所以是 NULL」在数据上可区分，便于日后统计真实覆盖率。
        RequestContextHolder.resetRequestAttributes();

        operationLogService.recordLog(USER_ID, TYPE, DESC, null);

        assertEquals("-", capture().getIpAddress());
    }

    // ==================== 主流程容错 ====================

    @Test
    public void recordLog_mapperThrows_doesNotPropagate() {
        // 审计写入失败绝不能影响主流程（这是 recordLog 既有语义，必须保持）
        request.setRemoteAddr("192.0.2.55");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(operationLogMapper).insert(any(OperationLog.class));

        // 不抛异常即通过
        operationLogService.recordLog(USER_ID, TYPE, DESC, null);
    }
}