package com.easychat.aspect;

import com.easychat.annotation.GlobalInterceptor;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link GlobalInterceptor} 注解的**跨 Controller 不变量**测试。
 *
 * <p><b>为什么需要这一层</b>：{@link GlobalOperationAspectTest} 测的是「切面收到某个注解组合后
 * 行为是否正确」，但它只能覆盖作者当时挑中的那几个端点。而真正会出事的是
 * <b>新加的端点标错注解</b>——例如给管理端接口写成裸 {@code @GlobalInterceptor}
 * （= 只校验登录，普通用户即可越权调用），或给未登录端点漏标 {@code checkRateLimit}
 * （= 限流形同虚设）。这两类都<b>不会抛异常、不会打警告</b>，冒烟与人工回归都很难发现。
 *
 * <p>2026-10-03 的实测事故正是后一类：{@code checkRateLimit} 在 {@code token == null} 时
 * 直接 return，而全仓 4 个 {@code checkLogin = false} 端点的限流<b>从未生效</b>。
 * 本类的「不变量 C」就是为防止同类复发而设。
 *
 * <p><b>刻意不做「注解矩阵快照对比」</b>：快照会把「新增一个正常端点」也变成失败，
 * 于是大家会去改快照绕过测试——久而久之快照失去意义。改为断言<b>不变量</b>：
 * 新增端点只要标注正确就不会红，标注错了才红。
 *
 * <p><b>三条不变量</b>：
 * <ol>
 *   <li><b>A 未授权端点</b>：任何映射方法都必须带 {@code @GlobalInterceptor}，
 *       否则等于<b>完全裸奔</b>（无鉴权、无限流）。唯一例外是显式白名单。</li>
 *   <li><b>B 管理端越权</b>：类级 {@code @RequestMapping} 以 {@code /admin} 开头的 Controller，
 *       其每个映射方法的 {@code checkAdmin()} 必须为 true——否则任何登录用户都能调管理接口。</li>
 *   <li><b>C 未登录端点必须限流</b>：{@code checkLogin = false} 的端点必须同时
 *       {@code checkRateLimit = true}，否则该端点对匿名请求完全不限流。</li>
 * </ol>
 *
 * @since 2026-10-03（AGENTS §8「鉴权白名单」L4 面的机控兜底）
 */
public class GlobalInterceptorAnnotationContractTest {

    private static final String CONTROLLER_PKG = "com.easychat.controller";

    /**
     * 无需登录的端点白名单。
     *
     * <p><b>新增条目必须写明理由</b>——白名单是「已知的安全例外」，
     * 无理由的白名单会退化成「想跳过就加一行」。
     */
    private static final Map<String, String> NO_INTERCEPTOR_ALLOWLIST = new LinkedHashMap<>();

    static {
        // 登录/注册所需的图形验证码端点：用户此时还没有 token，
        // 它本身就是获取 token 的前置步骤，加鉴权会造成死循环。
        NO_INTERCEPTOR_ALLOWLIST.put(
                "com.easychat.controller.AccountController#checkCode",
                "图形验证码端点是登录的前置步骤，此时用户尚无 token，加鉴权会死锁");
    }

    /** 端点矩阵：FQN#method → 注解组合描述 */
    private static final Map<String, String> MATRIX = new LinkedHashMap<>();
    private static List<String> controllerClasses;

    @BeforeClass
    public static void scan() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.web.bind.annotation.RestController.class));

        Set<String> names = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(CONTROLLER_PKG)) {
            names.add(bd.getBeanClassName());
        }
        controllerClasses = new ArrayList<>(names);
    }

    private static boolean isMapping(Method m) {
        return m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(GetMapping.class);
    }

    private static String prefixOf(Class<?> c) {
        RequestMapping rm = c.getAnnotation(RequestMapping.class);
        if (rm == null || rm.value().length == 0) {
            return "";
        }
        return rm.value()[0];
    }

    // ==================== 前置守卫 ====================

    /**
     * 扫描器本身坏掉时（包名改错、过滤器写错）会让下面所有断言**空转通过**——
     * 「没有端点违反不变量」和「根本没扫到端点」在断言层面无法区分。
     * 故先钉死「确实扫到了足够多的 Controller」，与 verify_schema_drift.mjs
     * 的解析器自检是同一条纪律。
     */
    @Test
    public void scanner_actuallyFindsControllers() {
        assertTrue(
                "扫描 com.easychat.controller 未找到任何 @RestController，"
                        + "若为空则本类全部不变量断言都会空转通过（详见类注释「前置守卫」）",
                controllerClasses.size() >= 10
        );
        // 当前 Controller 数（2026-10-03：22 个）。下限断言即可，不做精确匹配——
        // 新增 Controller 不该让本测试变红，那属于「正常演进」。
        assertTrue("扫描到的 Controller 数量异常偏少：" + controllerClasses.size(),
                controllerClasses.size() <= 60);
    }

    // ==================== 不变量 A：不允许裸奔端点 ====================

    @Test
    public void invariantA_everyEndpointHasInterceptorUnlessAllowlisted() {
        List<String> violations = new ArrayList<>();
        for (String cn : controllerClasses) {
            Class<?> c;
            try {
                c = Class.forName(cn);
            } catch (Throwable t) {
                violations.add(cn + " → 类加载失败：" + t);
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!isMapping(m)) continue;
                String key = cn + "#" + m.getName();
                GlobalInterceptor gi = m.getAnnotation(GlobalInterceptor.class);
                if (gi == null) {
                    if (NO_INTERCEPTOR_ALLOWLIST.containsKey(key)) continue;
                    violations.add(key + " 缺少 @GlobalInterceptor（完全无鉴权且无限流）");
                    continue;
                }
                MATRIX.put(key, describe(gi));
            }
        }
        assertTrue("存在未标注 @GlobalInterceptor 的端点：\n  " + String.join("\n  ", violations),
                violations.isEmpty());
    }

    // ==================== 不变量 B：/admin 必须 checkAdmin ====================

    @Test
    public void invariantB_adminEndpointsAlwaysCheckAdmin() {
        List<String> violations = new ArrayList<>();
        for (String cn : controllerClasses) {
            Class<?> c;
            try {
                c = Class.forName(cn);
            } catch (Throwable t) {
                continue;
            }
            String prefix = prefixOf(c);
            if (!prefix.startsWith("/admin")) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (!isMapping(m)) continue;
                GlobalInterceptor gi = m.getAnnotation(GlobalInterceptor.class);
                if (gi == null) continue; // 已被不变量 A 报出，这里不重复
                if (!gi.checkAdmin()) {
                    violations.add(cn + "#" + m.getName()
                            + " 位于 " + prefix + " 前缀下，但 checkAdmin() 为 false"
                            + " → 任何登录用户都可调用管理接口");
                }
            }
        }
        assertTrue("管理端端点未开启管理员校验：\n  " + String.join("\n  ", violations),
                violations.isEmpty());
    }

    // ==================== 不变量 C：未登录端点必须限流 ====================

    @Test
    public void invariantC_anonymousEndpointsMustBeRateLimited() {
        List<String> violations = new ArrayList<>();
        for (String cn : controllerClasses) {
            Class<?> c;
            try {
                c = Class.forName(cn);
            } catch (Throwable t) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!isMapping(m)) continue;
                GlobalInterceptor gi = m.getAnnotation(GlobalInterceptor.class);
                if (gi == null) continue;
                if (!gi.checkLogin() && !gi.checkAdmin() && !gi.checkRateLimit()) {
                    violations.add(cn + "#" + m.getName()
                            + " 为未登录端点（checkLogin=false）却未开启 checkRateLimit"
                            + " → 对匿名请求完全不限流");
                }
            }
        }
        assertTrue("未登录端点未开启限流：\n  " + String.join("\n  ", violations),
                violations.isEmpty());
    }

    // ==================== 矩阵输出（供人工审阅，非断言） ====================

    private static String describe(GlobalInterceptor gi) {
        StringBuilder sb = new StringBuilder();
        sb.append("checkLogin=").append(gi.checkLogin());
        if (gi.checkAdmin()) sb.append(" +checkAdmin");
        if (gi.checkRateLimit()) sb.append(" +checkRateLimit");
        return sb.toString();
    }

    @Test
    public void matrix_isReportedForReview() {
        // 不作断言，只输出端点矩阵：让人工 review 时能一眼看到注解分布是否合理。
        // 若扫描为空，MATRIX 也为空——但那条已由 scanner_actuallyFindsControllers 兜住。
        Map<String, String> sorted = new LinkedHashMap<>();
        new TreeSet<>(MATRIX.keySet()).forEach(k -> sorted.put(k, MATRIX.get(k)));
        System.out.println("[鉴权注解矩阵] 共 " + sorted.size() + " 个受保护端点：");
        sorted.forEach((k, v) -> System.out.println("  " + k + "  →  " + v));
        assertEquals("端点矩阵条目数不应为 0（否则说明扫描空转）", true, sorted.size() > 0);
    }
}