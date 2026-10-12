package com.easychat.annotation;

import org.springframework.web.bind.annotation.Mapping;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Mapping
public @interface GlobalInterceptor {

    /**
     * 校验登录
     *
     * @return
     */
    boolean checkLogin() default true;

    /**
     * 校验管理员
     *
     * @return
     */
    boolean checkAdmin() default false;

    /**
     * 校验 API 限流
     *
     * @return
     */
    boolean checkRateLimit() default false;

    /**
     * 限流维度参数名：非空时，限流按「该请求参数的值」计数，而非按 token / IP。
     *
     * <p>2026-10-12 新增（docs/system-facts.md §14 遗留 #1，人工决策「限流改按 email 计数」）。
     *
     * <p><b>为什么需要它</b>：默认的 IP 维度取 {@code X-Forwarded-For} 首段，
     * 而该头<strong>客户端可伪造</strong>（见 {@code IpTools#getClientIp} 的 javadoc），
     * EasyChat 又是直连部署（本机 127.0.0.1:5050）——攻击者伪造该头即可获得
     * 多个独立限流配额，使「以某邮箱的验证码为目标」的暴力破解实际不受约束。
     * 对这类端点，限流维度必须落在<strong>需要保护的客体</strong>（目标邮箱）上。
     *
     * <p><b>适用面</b>：仅限「以某个账号标识为目标」的端点（当前为 {@code sendEmailCode}
     * 与 {@code resetPassword}）。{@code login} / {@code register} <strong>不</strong>改维度——
     * 它们防的是密码暴力而非验证码暴力，且已有图形验证码把关。
     *
     * <p><b>取不到值时</b>：退回 IP 维度。刻意<strong>不</strong>退化成「全体匿名用户共用
     * 一个空前缀键」——那等于把限流变成 DoS 放大器。
     *
     * @return 请求参数名，空串（默认）表示沿用 token / IP 维度
     */
    String rateLimitByParam() default "";
}
