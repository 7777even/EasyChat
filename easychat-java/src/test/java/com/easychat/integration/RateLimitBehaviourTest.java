package com.easychat.integration;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.redis.RedisUtils;
import com.easychat.service.impl.ChatMessageServiceImpl;
import com.easychat.service.impl.UserInfoServiceImpl;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * T8 —— 限流守卫的**标注差异**行为（补齐 capability C1 的限流侧）。
 *
 * <p><b>本类要防的是「恒不放行 / 恒限流」这类不可区分的假通过</b>：
 * 若只断言「超阈值返回 1001」，那么把限流阈值改成 0（所有请求都被拒）同样能让它通过；
 * 若只断言「正常请求通过」，那么把限流逻辑整个删掉也能通过。故本类用<b>三个相互制衡</b>的用例：
 * <ol>
 *   <li>超阈值（incr=61）→ 必被拒；</li>
 *   <li>正好等于阈值（incr=60）→ 必放行 —— 与 ① 一起把<b>边界方向</b>钉死
 *       （静态断言抓不到 {@code >} 写成 {@code >=} 这类改动）；</li>
 *   <li>无 {@code checkRateLimit} 标注的端点 → {@code incr} <b>从未被调用</b>
 *       —— 证明「限流是按标注生效的」，而不是所有端点碰巧都限流。</li>
 * </ol>
 *
 * <p>⚠ <b>为什么必须用合法参数</b>：参数校验（{@code @Valid} / {@code @NotEmpty}）由
 * {@code HandlerMethodArgumentResolver} 在<b>方法调用之前</b>完成，而限流拦截器是 AOP 的
 * {@code @Before}。故非法参数会先抛 {@code BindException → CODE_1001}，
 * 限流分支<b>根本不会执行</b>，用它测限流只会得到一个「假红」。
 * 这个顺序是读拦截器与 Spring MVC 调用链得出的，实施时以断言结果反查确认。
 */
public class RateLimitBehaviourTest extends IntegrationTestBase {

    /** 有限流标注的端点（checkLogin=false, checkRateLimit=true） */
    private static final String RATE_LIMITED_URL = "/account/login";
    /** 无 checkRateLimit 标注的端点（@GlobalInterceptor 默认 checkLogin=true, checkRateLimit=false） */
    private static final String NOT_RATE_LIMITED_URL = "/chat/loadHistoryMessage";

    private static final int RATE_LIMIT_PER_MINUTE = 60;

    /**
     * 限流的独立错误码（2026-10-14 error-code-split 引入）。
     *
     * <p>🔴 <b>本类是「为什么需要拆码」的活证据</b>。拆码之前，限流抛的是
     * {@code CODE_1001}，与「验证码已过期」等业务错误<b>完全同码</b>，
     * 于是下面第 ② 条用例被迫三次改断言形态（见其 javadoc），
     * 最终只能断言「响应文案不含『请求过于频繁』」——
     * 而那正是本仓反复吃过亏的<b>脆弱断言</b>（还曾因 ISO-8859-1 解码而<b>恒真</b>）。
     *
     * <p>拆码后：限流有独立码，可直接用 {@code code} 判定，断言恢复稳固。
     */
    private static final int RATE_LIMIT_CODE = 1004;

    @MockBean
    private RedisUtils redisUtils;

    @MockBean
    private UserInfoServiceImpl userInfoService;

    @MockBean
    private ChatMessageServiceImpl chatMessageService;

    @Autowired
    private MockMvc mockMvc;

    @Before
    public void resetMocks() {
        Mockito.reset(redisUtils, userInfoService, chatMessageService);
    }

    /** 合法登录参数：UserLoginDTO 的 4 个 @NotEmpty 字段 + email 需符合 @Email */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validLogin() {
        return post(RATE_LIMITED_URL)
                .param("checkCodeKey", "ck")
                .param("email", "someone@example.com")
                .param("password", "pwd")
                .param("checkCode", "code");
    }

    /** ① 超阈值 → 被限流（独立码 CODE_1004） */
    @Test
    public void requestOverThresholdIsRateLimited() throws Exception {
        when(redisUtils.incr(anyString())).thenReturn((long) RATE_LIMIT_PER_MINUTE + 1);

        mockMvc.perform(validLogin())
                .andExpect(jsonPath("$.code").value(RATE_LIMIT_CODE));
    }

    /**
     * ② 正好等于阈值 → 放行（钉住 {@code count > 60} 的边界方向）。
     *
     * <p>🔴 <b>2026-10-14 起本条改用 {@code code} 判定，不再依赖中文文案。</b>
     *
     * <p><b>拆码之前，这里被迫做过三次断言形态修正，每一次都是因为「测错了东西」：</b>
     * <ol>
     *   <li>初版断言 {@code jsonPath("$.code") != 1001} —— 失败，因放行后响应体可能为空，取不到值；</li>
     *   <li>二版断言「body 不含 {@code "code":1001}」—— <b>仍失败，且失败信息极具误导性</b>：
     *       实测 body 为 {@code {"code":1001,"message":"验证码已过期"}} ——
     *       该 1001 来自<b>业务逻辑</b>（登录校验验证码），<b>限流根本没触发</b>。
     *       即限流与业务错误<b>共用同一个码</b>，用 code 判断限流属于测错对象。</li>
     *   <li>三版只能断言「message 不含限流文案」—— 而那正是本仓反复吃亏的<b>脆弱断言</b>：
     *       {@code getContentAsString()} 默认按 <b>ISO-8859-1</b> 解码，
     *       中文被解成乱码 → {@code contains(...)} <b>恒为 false</b> → <b>断言恒真</b>。
     *       变异「{@code count > 60} 写成 {@code >=}」后本应转红，实测<b>仍然全绿</b>。</li>
     * </ol>
     *
     * <p><b>拆码后这一条回到最稳固的形态</b>：只看 code，与字符集、文案均无关。
     * 这就是本 Change 的直接价值 —— 不是「码变好看了」，而是<b>断言重新变得可信</b>。
     */
    @Test
    public void requestExactlyAtThresholdIsAllowed() throws Exception {
        when(redisUtils.incr(anyString())).thenReturn((long) RATE_LIMIT_PER_MINUTE);

        String body = mockMvc.perform(validLogin()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.junit.Assert.assertFalse(
                "恰好等于阈值时被限流了，说明阈值判定方向写反（应为 count > 60）：body=" + body,
                body.contains("\"code\":" + RATE_LIMIT_CODE));
    }

    /**
     * ③ 无 {@code checkRateLimit} 标注的端点 → 限流分支<b>完全不执行</b>。
     *
     * <p>判别力说明：本条<b>不依赖</b>「响应是什么」，而是三条 verify ——
     * {@code get(...)} 被调用（证明拦截器<b>确实执行了</b>，否则本条会假通过）、
     * {@code incr(...)} 与 {@code expire(...)} <b>从未被调用</b>（证明限流分支没走）。
     *
     * <p>🔴 踩坑：初版写 {@code verify(redisUtils).get(...)}（恰好一次），实测
     * {@code TooManyActualInvocations: But was 2 times} —— 因为
     * {@code GlobalOperationAspect#checkLogin} 取一次，
     * {@code ABaseController#getTokenUserInfo} 又取一次（这条链路正是台账里记的
     * 「鉴权取用户须走基类方法」）。改为 {@code atLeastOnce()}：本用例要证明的是
     * 「拦截器<b>执行过</b>」，不是「只执行一次」。
     */
    @Test
    public void endpointWithoutRateLimitAnnotationNeverTouchesLimiter() throws Exception {
        TokenUserInfoDto user = new TokenUserInfoDto();
        user.setToken("tok");
        user.setUserId("U001");
        user.setAdmin(Boolean.FALSE);
        when(redisUtils.get(anyString())).thenReturn(user);

        mockMvc.perform(post(NOT_RATE_LIMITED_URL)
                .header("token", "tok")
                .param("sessionId", "S1"));

        verify(redisUtils, org.mockito.Mockito.atLeastOnce()).get(anyString());
        verify(redisUtils, never()).incr(anyString());            // 但没走限流分支
        verify(redisUtils, never()).expire(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}