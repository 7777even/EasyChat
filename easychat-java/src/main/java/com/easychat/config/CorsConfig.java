package com.easychat.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局 CORS 跨域配置（Filter 级别，最高优先级）
 * 解决 AOP 拦截器在 CORS 之前执行导致 OPTIONS 预检失败的问题
 *
 * <p><b>2026-10-21（L4，人工已确认）</b>：把「允许来源」从硬编码改为**可配置**，
 * 并对「凭据 + 通配来源」这一组合在启动时**显式告警**。
 * <ul>
 *   <li><b>默认行为不变</b>：默认仍为 {@code *}（与改动前逐字一致），
 *       故<b>不会</b>打断桌面端（Electron 渲染层跨源访问后端）的现有用法；</li>
 *   <li><b>暴露面不变、但从隐性变显性</b>：改动前 {@code allowCredentials(true)}
 *       配 {@code allowedOriginPattern("*")} 是一颗<b>哑弹</b>——
 *       当前会话用 <b>token 请求头</b>（凭据不由浏览器自动携带），所以任意站点无法借此读到响应；
 *       但<b>一旦任何端点改用 Cookie 会话</b>，它立刻变成 CSRF 暴露面。
 *       改动后这个组合会在<b>启动日志里被点名</b>，且有明确的收紧开关；</li>
 *   <li>收紧方式：{@code easychat.cors.allowed-origin-patterns=<逗号分隔的来源白名单>}。</li>
 * </ul>
 */
@Configuration
public class CorsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    /**
     * 允许的跨域来源模式（逗号分隔）。默认 {@code *} = **维持现状**。
     *
     * <p>⚠ 收紧前请确认：桌面端渲染层（file:// 或本地 dev server）与后端不同源，
     * 收紧到具体来源前必须把它加进白名单，否则**应用会立刻无法访问后端**。
     */
    @Value("${easychat.cors.allowed-origin-patterns:*}")
    private String allowedOriginPatterns = "*";

    // ── 允许来源解析与宽松判定（纯函数，可单测）────────────────────────

    /**
     * 解析允许来源列表：逗号分隔 → 去空白 → 丢空项。
     *
     * <p><b>空值回退到 {@code "*"}</b>：误配（如把配置写成空串）**不应把应用打死** ——
     * 桌面端渲染层与后端不同源，收紧到「没有来源」会让应用立刻不可用。
     * 回退时由 {@link #warnOnPermissiveOrigin()} 打 ERROR 把「配置无效」摆到台面上。
     */
    static List<String> parseOriginPatterns(String raw) {
        if (raw == null) {
            return Collections.singletonList("*");
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            return Collections.singletonList("*");
        }
        return out;
    }

    /** 来源列表里是否含通配符（含前缀通配如 {@code http://*}）—— 与凭据组合即 CSRF 暴露面。 */
    static boolean isWildcardOrigin(List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String p : patterns) {
            if (p != null && p.contains("*")) {
                return true;
            }
        }
        return false;
    }

    /** 配置是否「空」（未填 / 全是逗号）—— 用于启动时区分「回退」与「显式配了 *」。 */
    static boolean isBlankConfig(String raw) {
        if (raw == null) {
            return true;
        }
        for (String part : raw.split(",")) {
            if (!part.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 「凭据 + 通配来源」组合的告警文案；非宽松组合返回 {@code null}（不告警）。
     *
     * <p>措辞要点：说清**当前为什么不构成暴露面**（会话用 token 请求头，凭据不由浏览器自动携带），
     * 以及**什么时候会构成**（任何端点改用 Cookie 会话）—— 否则会被当成「又一条噪音告警」忽略掉。
     */
    static String permissiveWarning(List<String> patterns) {
        if (!isWildcardOrigin(patterns)) {
            return null;
        }
        return "[CORS] 允许来源含通配符（" + patterns + "）且开启了 allowCredentials(true) —— "
                + "当前会话用 **token 请求头**（凭据不由浏览器自动携带），任意站点无法借此读到响应，"
                + "**故目前不构成 CSRF 暴露面**；但**一旦任何端点改用 Cookie 会话，它立刻成为 CSRF 暴露面**。"
                + "收紧方式：设置 easychat.cors.allowed-origin-patterns=<逗号分隔的来源白名单>"
                + "（收紧前请确认桌面端渲染层的来源已在白名单内，否则应用将无法访问后端）。";
    }

    /**
     * 启动自检：把「凭据 + 通配来源」这个隐性组合**显式点名**。
     *
     * <p>这是本次 L4 变更的核心：<b>默认行为一字未改</b>（仍是 {@code *}），
     * 但这颗哑弹从此在启动日志里可见，且有明确的收紧开关 —— 而不是只躺在台账里。
     */
    @PostConstruct
    public void warnOnPermissiveOrigin() {
        if (isBlankConfig(allowedOriginPatterns)) {
            logger.error("[CORS] easychat.cors.allowed-origin-patterns 为空 → 已回退到 \"*\"（维持现状）。"
                    + "若意图是禁止跨域，请改用其它手段（例如只监听同源 + 由网关收敛）。");
        }
        String warning = permissiveWarning(parseOriginPatterns(allowedOriginPatterns));
        if (warning != null) {
            logger.warn(warning);
        }
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        for (String pattern : parseOriginPatterns(allowedOriginPatterns)) {
            config.addAllowedOriginPattern(pattern);
        }
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
