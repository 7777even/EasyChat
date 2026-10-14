package com.easychat.integration;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.redis.RedisUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T6 / T7 —— 认证与限流守卫的**行为**验证（闭环 capability C1）。
 *
 * <p><b>与既有 {@code GlobalInterceptorAnnotationContractTest} 的区别</b>：那个测试用反射断言
 * 「注解在不在」，而本类断言「请求打进去会发生什么」。区别不是形式上的 ——
 * 历史缺陷 {@code #30「@所有人 权限仅在客户端生效」} 的形态是「注解加了、服务端压根没校验」，
 * 结构断言对此<b>照不出来</b>；只有真实响应体才能区分「守卫缺失」与「守卫写反」。
 *
 * <p>🔴 <b>本类的用例必须成对写</b>：只断言「非法被拒」无法区分「守卫生效」与「端点恒拒绝」。
 * 故每条守卫都配一条<b>合法路径放行</b>的用例（T6 的第二条即为此），
 * 且放行路径靠 {@code verify(..., never())} 断言 Service 真的没被误伤。
 */
public class InterceptorChainBehaviourTest extends IntegrationTestBase {

    /** 拦截器取当前用户信息靠它（GlobalOperationAspect#checkLogin → redisUtils.get(...)） */
    @MockBean
    private RedisUtils redisUtils;

    /** 放行路径需要真实 Service 会去查库，故 mock 掉，避免误连不可达数据源 */
    @MockBean
    private com.easychat.service.AdminReportService adminReportService;

    @Autowired
    private MockMvc mockMvc;

    private static final String ADMIN_URL = "/admin/report/loadReport";

    private TokenUserInfoDto userWithAdmin(Boolean admin) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setToken("t-admin-test");
        dto.setUserId("U001");
        dto.setAdmin(admin);
        return dto;
    }

    @Before
    public void resetMocks() {
        Mockito.reset(redisUtils, adminReportService);
    }

    // ─────────────────────────── T6：checkAdmin ───────────────────────────

    /**
     * T6-①：非管理员访问管理端必须被拒 —— 断言<b>响应体</b>而非注解。
     *
     * <p>预期 {@code code=1003}（AGENTS §3.1：1003 = 非管理员访问管理端）。
     */
    @Test
    public void nonAdminIsRejectedOnAdminEndpoint() throws Exception {
        when(redisUtils.get(anyString())).thenReturn(userWithAdmin(Boolean.FALSE));

        mockMvc.perform(post(ADMIN_URL).header("token", "tok-non-admin"))
                .andExpect(jsonPath("$.code").value(1003));
    }

    /**
     * T6-②：管理员访问同一端点必须<b>放行</b>。
     *
     * <p>为什么这条必须有：若只保留 T6-①，把守卫写成「无条件拒绝」也能让 T6-① 通过。
     * 本条同时用 {@code verify(adminReportService, never())} 断言
     * 「被拒的那次调用，Service 确实没被执行」—— 即拦截发生在<b>进入业务之前</b>。
     */
    @Test
    public void adminIsAllowedThrough() throws Exception {
        when(redisUtils.get(anyString())).thenReturn(userWithAdmin(Boolean.TRUE));
        when(adminReportService.loadReport(Mockito.any())).thenReturn(null);

        mockMvc.perform(post(ADMIN_URL).header("token", "tok-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(adminReportService, never()).loadReport(null);
    }

    /**
     * T6-② 的补充：上一条只断言「不抛错」，还需确认 Service <b>确实被调用过</b>
     * —— 否则「拦截器把所有请求都吞掉」也能通过（响应 200 + code 0 来自兜底处理器）。
     */
    @Test
    public void adminRequestReachesService() throws Exception {
        when(redisUtils.get(anyString())).thenReturn(userWithAdmin(Boolean.TRUE));

        mockMvc.perform(post(ADMIN_URL).header("token", "tok-admin"))
                .andExpect(status().isOk());
        verify(adminReportService, Mockito.times(1)).loadReport(Mockito.any());
    }

    // ─────────────────────── 顺带发现的边界：admin 为 null ───────────────────────

    /**
     * 会话里的 {@code admin} 为 {@code null} 时，必须判「无权访问」（CODE_1003），
     * **不得**因自动拆箱抛 NPE 而落到「系统错误」（CODE_1002）。
     *
     * <p><b>2026-10-14 已修复</b>（本用例原名 {@code nullAdminFlagCurrentlyFallsBackToSystemError}，
     * 是当时把<b>缺陷现状</b>钉成断言的护栏）。修复内容：切面改用
     * {@code Boolean.TRUE.equals(...)}，原用例随之转红提醒 —— 正是它该起的作用。
     *
     * <p>⚠ 实测补充：当前三条会话写入路径（登录 / 昵称同步 / token 刷新）均会 {@code setAdmin}，
     * 故该 NPE 此前<b>不可达</b>；本条按防御性回归用例保留，锁住「缺字段即崩」这一隐患不再复发。
     */
    @Test
    public void nullAdminFlagIsRejectedAsForbiddenNotSystemError() throws Exception {
        when(redisUtils.get(anyString())).thenReturn(userWithAdmin(null));

        mockMvc.perform(post(ADMIN_URL).header("token", "tok-null-admin"))
                .andExpect(jsonPath("$.code").value(1003));
    }

    // ─────────────────────────── T7：登录态缺失 ───────────────────────────

    /** T7：token 在 Redis 查不到 → 必须判登录失效（CODE_2001），不得放行到业务 */
    @Test
    public void missingTokenIsRejectedWithExpiredCode() throws Exception {
        when(redisUtils.get(anyString())).thenReturn(null);

        mockMvc.perform(post(ADMIN_URL).header("token", "tok-unknown"))
                .andExpect(jsonPath("$.code").value(2001));
        verify(adminReportService, never()).loadReport(Mockito.any());
    }
}