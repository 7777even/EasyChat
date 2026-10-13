package com.easychat.controller;

import com.easychat.entity.po.UserInfoBeauty;
import com.easychat.service.UserInfoBeautyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminUserInfoBeautyController} 的**行为**测试（MockMvc 真实路由 + 真实全局异常处理器）。
 *
 * <p>背景：`docs/system-facts.md` §14 遗留 #31 的落点列留了一条未修的偏差登记 ——
 * {@code saveBeautAccount} 直接收 {@code UserInfoBeauty} **PO**，违反 AGENTS §3.6
 * 「不复用 Entity 做出入参」。
 *
 * <p>⚠️ <b>这不是安全缺口，收敛的理由是结构性的</b>（立项前已穷举后果链）：
 * ① 该端点有 {@code @GlobalInterceptor(checkAdmin = true)} 守着；
 * ② {@code UserInfoBeautyServiceImpl#saveAccount} 已有四重校验，
 *    2026-10-12 遗留 #19 又补了 {@code checkBeautyUserId}（须纯数字）；
 * ③ PO 上仅 4 个字段且都是管理员域内可改的数据，<b>无跨用户越权</b>。
 * 真正的风险是：<b>PO 直接绑定 ⇒ 将来给这个 PO 加一个内部字段，它会「自动」
 * 变成可由外部提交的入参，且构建 / 测试 / 门禁都不会给任何提示。</b>
 *
 * <p>本测试锁定的点：
 * <ol>
 *   <li>入参形状：Controller 方法参数**不是** PO（结构断言，见类尾说明）</li>
 *   <li>必填校验：{@code email} 空 / 邮箱格式非法 / {@code userId} 空 → 400 + 1001，且**不进 Service**</li>
 *   <li>转换完整性：DTO → PO **逐字段**赋值，不靠反射拷贝（否则新增字段会被静默漏掉）</li>
 *   <li>新建 / 编辑分流语义不被 DTO 干扰：{@code id} 空与非空都**不**报错，由 Service 分流</li>
 *   <li>响应包络：成功一律 {@code code=0}（§3.1 禁止第二套响应外壳）</li>
 * </ol>
 */
class AdminUserInfoBeautyControllerMockMvcTest {

    @Mock
    private UserInfoBeautyService userInfoBeautyService;

    @InjectMocks
    private AdminUserInfoBeautyController controller;

    private MockMvc mockMvc;

    private final AGlobalExceptionHandlerController exceptionHandler = new AGlobalExceptionHandlerController();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    // ==================== 入参必填校验 ====================

    @Test
    @DisplayName("email 为空 → 400 + 1001，且不进 Service")
    void saveAccount_emailBlank_isRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/admin/saveBeautAccount")
                        .param("email", "")
                        .param("userId", "U01234567890")
                        .param("status", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        verify(userInfoBeautyService, never()).saveAccount(any());
    }

    @Test
    @DisplayName("email 格式非法 → 400 + 1001，且不进 Service")
    void saveAccount_emailMalformed_isRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/admin/saveBeautAccount")
                        .param("email", "not-an-email")
                        .param("userId", "U01234567890")
                        .param("status", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        verify(userInfoBeautyService, never()).saveAccount(any());
    }

    @Test
    @DisplayName("userId 为空 → 400 + 1001，且不进 Service")
    void saveAccount_userIdBlank_isRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/admin/saveBeautAccount")
                        .param("email", "beauty@example.com")
                        .param("userId", "")
                        .param("status", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        verify(userInfoBeautyService, never()).saveAccount(any());
    }

    // ==================== 合法请求：转换完整性 ====================

    @Test
    @DisplayName("合法新建请求 → code=0，且 DTO→PO 逐字段完整转换")
    void saveAccount_validCreate_mapsEveryField() throws Exception {
        mockMvc.perform(post("/admin/saveBeautAccount")
                        .param("email", "beauty@example.com")
                        .param("userId", "U01234567890")
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<UserInfoBeauty> captor = ArgumentCaptor.forClass(UserInfoBeauty.class);
        verify(userInfoBeautyService).saveAccount(captor.capture());

        UserInfoBeauty saved = captor.getValue();
        assertEquals("beauty@example.com", saved.getEmail());
        assertEquals("U01234567890", saved.getUserId());
        assertEquals(0, saved.getStatus());
        // 新建：id 不带下来，由 Service 按 null 分流
        assertNull(saved.getId());
    }

    @Test
    @DisplayName("合法编辑请求 → id 透传给 Service（由其走 updateById 分支）")
    void saveAccount_validEdit_passesIdThrough() throws Exception {
        mockMvc.perform(post("/admin/saveBeautAccount")
                        .param("id", "17")
                        .param("email", "beauty@example.com")
                        .param("userId", "U01234567890")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<UserInfoBeauty> captor = ArgumentCaptor.forClass(UserInfoBeauty.class);
        verify(userInfoBeautyService).saveAccount(captor.capture());
        assertEquals(17, captor.getValue().getId());
        assertEquals(1, captor.getValue().getStatus());
    }

    // ==================== §3.6 契约：入参不得是 PO ====================
    //
    // ⚠ 为什么用结构断言而不是行为断言：PO 上**没有**校验注解，
    //   若哪天有人「顺手」把 saveBeautAccount 的入参改回 PO，
    //   上面 6 条行为用例会**全部照常通过**（PO 无约束 ⇒ 不触发校验；
    //   字段名相同 ⇒ 绑定与转换都正常）。这正是 AGENTS §2.1 第 3 条说的
    //   「静态断言有固有上限」的反面：此处必须有一道**只看形状**的断言兜底。
    @Test
    @DisplayName("§3.6：入参不得是 entity.po 下的持久化对象")
    void saveAccount_parameterMustNotBePersistEntity() throws Exception {
        Class<?> paramType = null;
        for (java.lang.reflect.Method m : AdminUserInfoBeautyController.class.getDeclaredMethods()) {
            if ("saveBeautAccount".equals(m.getName())) {
                paramType = m.getParameterTypes()[0];
            }
        }
        assertEquals(true, paramType != null, "未找到 saveBeautAccount 方法");
        assertEquals(false, UserInfoBeauty.class.equals(paramType),
                "saveBeautAccount 入参仍是持久化对象 UserInfoBeauty，违反 AGENTS §3.6「不复用 Entity 做出入参」");
        assertEquals(true, paramType.getPackage() != null
                        && paramType.getPackage().getName().endsWith(".entity.dto"),
                "saveBeautAccount 入参应位于 entity.dto 包下，实际为 " + paramType.getName());
    }

    // ==================== 其他端点不受影响 ====================

    // ==================== 其他端点不受影响 ====================

    /**
     * `delBeautAccount(@NotNull Integer id)` 的方法参数约束。
     *
     * <p>⚠️ <b>刻意写成结构断言而非行为断言</b>：初版写成
     * 「id 为空 → 400 + 1001」，实测恒红（返回 200）。
     * 根因**不是产品行为**，而是 {@code standaloneSetup} 不注册
     * {@code MethodValidationPostProcessor}，类级 {@code @Validated} + 方法参数注解
     * 在本测试环境下**根本不触发** —— 这正是台账 §14 遗留 #32 登记的现象。
     *
     * <p>若照初版写，会得到一条「因环境缺件而恒红」的用例：
     * 它既验证不了产品，也会在未来有人修好测试装配时变成假失败。
     * 故此处只断言「约束注解确实声明在参数上」，行为验证交由真机（已登记为 #32 覆盖缺口）。
     *
     * <p>注意它与本文件前面的 6 条**性质不同**：那 6 条走的是 DTO 字段级约束 +
     * {@code @Valid}，由 {@code ModelAttributeMethodProcessor} 触发，**在
     * {@code standaloneSetup} 下确实生效**（遗留 #2 的变异已实证过）。
     */
    @Test
    @DisplayName("delBeautAccount 的 id 参数声明了 @NotNull（行为验证受 #32 所限，见注释）")
    void delBeautAccount_idIsAnnotatedNotNull() throws Exception {
        boolean found = false;
        for (java.lang.reflect.Method m : AdminUserInfoBeautyController.class.getDeclaredMethods()) {
            if (!"delBeautAccount".equals(m.getName())) continue;
            found = true;
            // ⚠ getParameterAnnotations() 返回的是 Annotation[][]，
            //   但单参数方法对它做隐式降维时拿到的是单个 Annotation —— 故逐层取值，
            //   不能直接写 `for (Annotation[] a : m.getParameterAnnotations()[0])`（编译不过）。
            boolean hasNotNull = false;
            for (java.lang.annotation.Annotation[] paramAnns : m.getParameterAnnotations()) {
                for (java.lang.annotation.Annotation ann : paramAnns) {
                    if (ann.annotationType() == javax.validation.constraints.NotNull.class) {
                        hasNotNull = true;
                    }
                }
            }
            assertEquals(true, hasNotNull, "delBeautAccount 的 id 参数未声明 @NotNull");
        }
        assertEquals(true, found, "未找到 delBeautAccount 方法");
    }
}