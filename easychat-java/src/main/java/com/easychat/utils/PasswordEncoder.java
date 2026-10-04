package com.easychat.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密工具类（BCrypt）
 */
public class PasswordEncoder {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * BCrypt modular-crypt format 的全部已知版本前缀。
     *
     * <p>刻意<b>不</b>使用 {@code startsWith("$2")}：那会把无 minor 的 {@code "$2$"}
     * 与 {@code "$20$"}~{@code "$29$"} 一并放行，掩盖真正的非 BCrypt 数据。
     */
    private static final String[] BCRYPT_PREFIXES = {"$2a$", "$2b$", "$2x$", "$2y$"};

    /** BCrypt 哈希定长 60 字符。 */
    private static final int BCRYPT_HASH_LENGTH = 60;

    /**
     * 加密密码
     *
     * @param rawPassword 原始密码
     * @return BCrypt 哈希
     */
    public static String encode(String rawPassword) {
        if (StringTools.isEmpty(rawPassword)) {
            return null;
        }
        return encoder.encode(rawPassword);
    }

    /**
     * 验证密码
     *
     * @param rawPassword 原始密码
     * @param encodedPassword BCrypt 哈希
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        if (StringTools.isEmpty(rawPassword) || StringTools.isEmpty(encodedPassword)) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, encodedPassword);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断密码是否为 BCrypt 哈希。
     *
     * <p><b>本方法不得比 {@link #matches} 更严</b>——这是调用方
     * {@code UserInfoServiceImpl#login} 依赖的不变式：
     * <blockquote>
     * {@code isBCrypt(p) == false} ⟹ {@code matches(any, p)} 不可能成功
     * </blockquote>
     * 违反方向不对称：
     * <ul>
     *   <li><b>判严</b>（漏放变体）→ 密码走 MD5 双验证分支 → 登录后「MD5→BCrypt 自动升级」
     *       会把已加密的哈希再加密一次 → <b>不可逆损坏，该账号永久无法登录</b></li>
     *   <li><b>判松</b>（多放非 BCrypt）→ 校验失败 → 返回密码错误，<b>不改数据</b>，安全</li>
     * </ul>
     * 故此处只能向「放宽」方向收紧规则。
     *
     * <p><b>为何放行全部已知变体而非照抄当前 Spring 接受的集合</b>：
     * 实测 spring-security-crypto 5.6.0 只接受 {@code $2a$ / $2b$ / $2y$}（{@code $2x$} 被拒），
     * 而该集合会随依赖版本漂移。照抄它意味着一旦 Spring 新增支持某个变体，
     * 本方法立刻退化为「判严」形态且<b>无声</b>。放过比错过安全，故取规范全集。
     *
     * <p><b>为何同时要求定长 60</b>：BCrypt modular-crypt 格式长度恒为 60
     * （{@code $2<minor>$} 7 + cost 2 + {@code $} 1 + 盐 22 + 哈希 31）。
     * 这是格式定义而非启发式阈值，加上它可避免 {@code "$2a$"}、{@code "$2a$10$tooshort"}
     * 这类<b>根本不构成哈希的截断串</b>被认作 BCrypt。
     *
     * <p>注意：长度不等于 60 而前缀形似 BCrypt 时判 false，方向是安全的——
     * 调用方会落到 MD5 分支并校验失败，不会触发任何数据改写。
     *
     * @param password 密码哈希
     * @return 是否为 BCrypt 哈希
     */
    public static boolean isBCrypt(String password) {
        if (StringTools.isEmpty(password) || password.length() != BCRYPT_HASH_LENGTH) {
            return false;
        }
        for (String prefix : BCRYPT_PREFIXES) {
            if (password.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
