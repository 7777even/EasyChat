package com.easychat.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密工具类（BCrypt）
 */
public class PasswordEncoder {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

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
     * 判断密码是否为 BCrypt 哈希
     *
     * @param password 密码哈希
     * @return 是否为 BCrypt 哈希
     */
    public static boolean isBCrypt(String password) {
        return password != null && password.startsWith("$2a$");
    }
}
