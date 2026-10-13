package com.easychat.integration;

import com.easychat.redis.RedisUtils;
import com.easychat.service.impl.UserInfoServiceImpl;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * T9 / T10 / T11 —— 参数校验在**真实 Web 容器**中触发（闭环 capability C2 与遗留 #32）。
 *
 * <p><b>为什么必须用真容器</b>（遗留 #32 的成因）：{@code MockMvcBuilders.standaloneSetup}
 * <b>不注册</b> {@code MethodValidationPostProcessor}，故类级 {@code @Validated} + 方法参数注解
 * （如 {@code sendEmailCode(@NotEmpty String email, …)}）在单测环境下<b>根本不触发</b> ——
 * 用它写端到端断言必然恒红，那是<b>环境缺失</b>而非产品行为。
 * 本类用 {@code @SpringBootTest} + {@code @AutoConfigureMockMvc}（基于 {@code WebApplicationContext}，
 * <b>会</b>注册该处理器），把这条路径从「维持登记的盲区」变成真容器行为覆盖。
 *
 * <p><b>断言形态的选择（关键）</b>：本类**不以错误文案为主断言**，而是
 * {@code verify(service, never())} —— 「方法体<b>从未被执行</b>」才是参数校验的语义本质
 * （校验发生在参数解析 / AOP 代理层，早于方法体）。这样做有三重好处：
 * <ol>
 *   <li>不依赖 {@code @NotEmpty} 的<b>默认文案</b>（无自定义 message 时文案随 locale 变）；</li>
 *   <li>不受业务层错误码干扰 —— 本仓<b>限流与业务校验都共用 CODE_1001</b>，
 *       前面 {@code RateLimitBehaviourTest} 已实测踩过这个坑；</li>
 *   <li>判别力更强：若守卫被删，该 verify 立刻失败。</li>
 * </ol>
 */
public class ParamValidationBehaviourTest extends IntegrationTestBase {

    private static final String LOGIN_URL = "/account/login";
    private static final String SEND_EMAIL_CODE_URL = "/account/sendEmailCode";

    @MockBean
    private RedisUtils redisUtils;

    @MockBean
    private UserInfoServiceImpl userInfoService;

    @Autowired
    private MockMvc mockMvc;

    @Before
    public void resetMocks() {
        Mockito.reset(redisUtils, userInfoService);
        // 限流计数默认返回 null（applyRateLimit 对 null 不抛），使用例只聚焦参数校验
        when(redisUtils.incr(anyString())).thenReturn(1L);
    }

    private String bodyOf(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ───────────── T9：对象绑定校验（@Valid + @ModelAttribute）→ BindException ─────────────

    /**
     * T9-①：{@code login(@Valid UserLoginDTO dto)} 传<b>非法邮箱</b> → 必须被拒，
     * 且 <b>Service 方法体从未执行</b>。
     *
     * <p>非法邮箱而非空串：空邮箱会先命中同 DTO 上其它 {@code @NotEmpty} 的可能性更大，
     * 用格式非法的值可让错误<b>唯一确定地</b>落在 {@code @Email} 上。
     */
    @Test
    public void invalidEmailOnModelAttributeBindingIsRejectedBeforeMethodBody() throws Exception {
        String body = bodyOf(mockMvc.perform(post(LOGIN_URL)
                .param("checkCodeKey", "ck")
                .param("email", "not-an-email")
                .param("password", "pwd")
                .param("checkCode", "code")).andReturn());

        org.junit.Assert.assertTrue(
                "非法邮箱未被拒（BindException 路径未触发）：body=" + body,
                body.contains("\"code\":1001"));
        // ★ 核心断言：方法体从未执行 —— 这才是「参数校验生效」的语义
        //   （签名按实现类实际形态：login(String email, String password)）
        verify(userInfoService, never()).login(anyString(), anyString());
    }

    /**
     * T9-②：{@code BindException} 的文案形态是 <b>{@code 字段名: 提示}</b>
     * （见 {@code AGlobalExceptionHandlerController#handleBindException}），
     * 与方法级校验的「仅提示、无字段名前缀」<b>形态不同</b>，本条锁住前者。
     */
    @Test
    public void bindExceptionMessageCarriesFieldNamePrefix() throws Exception {
        String body = bodyOf(mockMvc.perform(post(LOGIN_URL)
                .param("checkCodeKey", "ck")
                .param("email", "not-an-email")
                .param("password", "pwd")
                .param("checkCode", "code")).andReturn());

        org.junit.Assert.assertTrue(
                "BindException 文案应含字段名前缀「email: 」，实际：body=" + body,
                body.contains("email: "));
    }

    // ───── T10：方法级校验（类级 @Validated + 参数 @NotEmpty）→ ConstraintViolationException ─────

    /**
     * T10-①：{@code sendEmailCode(@NotEmpty String email, Integer type)} 传空邮箱 → 必须被拒，
     * 且 <b>Service 方法体从未执行</b>。
     *
     * <p><b>本条就是遗留 #32 的闭环证据</b>：在 {@code standaloneSetup} 下它恒红（校验不触发），
     * 在真容器下应当转绿。若将来有人把测试改回 {@code standaloneSetup}，
     * 本用例会<b>立刻失败</b>而不是「假通过」—— 那正是 #32 当年只能改写成结构断言的原因。
     */
    @Test
    public void methodLevelValidationRejectsEmptyEmailBeforeMethodBody() throws Exception {
        String body = bodyOf(mockMvc.perform(post(SEND_EMAIL_CODE_URL)
                .param("email", "")
                .param("type", "0")).andReturn());

        org.junit.Assert.assertTrue(
                "方法级校验未触发（@Validated + @NotEmpty 形同虚设）：body=" + body,
                body.contains("\"code\":1001"));
        verify(userInfoService, never()).sendEmailCode(anyString(), ArgumentMatchers.anyInt());
    }

    // ───────────── T11：合法入参**不得**被误伤（否则前面三条可能是「恒拒绝」） ─────────────

    /**
     * T11：参数合法时<b>必须进入方法体</b>。
     *
     * <p>为什么这条不可省：若校验被写成「无条件抛错」，T9/T10 同样会绿。
     * 本条与它们构成制衡：<b>该拒的拒 + 不该拒的放行</b>，缺一不可。
     */
    @Test
    public void validParametersReachMethodBody() throws Exception {
        mockMvc.perform(post(SEND_EMAIL_CODE_URL)
                .param("email", "someone@example.com")
                .param("type", "0"));

        verify(userInfoService, times(1)).sendEmailCode(anyString(), ArgumentMatchers.anyInt());
    }

    /**
     * T11 的补充：合法入参时<b>限流计数照常发生</b>，即参数校验不是靠「拦掉一切」实现的。
     * 同时再次锁定「限流与参数校验共用 CODE_1001」这一事实 —— 故本类不以 code 判定校验。
     */
    @Test
    public void rateLimiterStillRunsWhenParametersAreValid() throws Exception {
        mockMvc.perform(post(SEND_EMAIL_CODE_URL)
                .param("email", "someone@example.com")
                .param("type", "0"));

        verify(redisUtils, times(1)).incr(anyString());
        verify(userInfoService, times(1)).sendEmailCode(anyString(), ArgumentMatchers.anyInt());
    }
}