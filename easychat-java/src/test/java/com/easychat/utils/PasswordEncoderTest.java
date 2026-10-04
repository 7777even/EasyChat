package com.easychat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BCrypt 密码编码工具类测试（2026-10-04，L4 change: bcrypt-variant-detection）
 *
 * <p>核心不变式（见 design.md §1）：
 * <blockquote>
 * {@code isBCrypt(p) == false} ⟹ {@code matches(anything, p)} 不可能成功
 * </blockquote>
 * 即格式判定<b>不得比校验更严</b>。违反方向不对称：
 * <ul>
 *   <li>判严了（漏放变体）→ 密码链路走 MD5 分支 → login:288 把已加密哈希再加密一次 → <b>不可逆损坏</b></li>
 *   <li>判松了（多放非 BCrypt）→ 校验失败 → 返回密码错误，<b>数据不动</b>，安全</li>
 * </ul>
 * 故修复只能向「放宽」方向。
 *
 * <p><b>测试口径为何是推导式的（ADR-002）</b>：若把「Spring 支持哪些变体」硬编码进测试，
 * 则依赖升级后测试与实现会基于同一份过时知识互相印证而<b>假绿</b>——正是
 * AGENTS §2.1 第 1 条「门禁须先有判别力」的反例。故组 A 先实测当前 Spring 的接受集，
 * 再断言「凡其接受者必须放行」；组 D 另设硬编码断言以发现「多拒」。
 */
@DisplayName("PasswordEncoder — BCrypt 变体识别")
class PasswordEncoderTest {

    private static final BCryptPasswordEncoder ENC = new BCryptPasswordEncoder();
    private static final String PWD = "secret123";

    /** 基准哈希，形如 {@code $2a$10$<22 位盐><31 位哈希>}。BCrypt 计算成本 10，逐次生成约 100ms，故只生成一次。 */
    private static final String BASE = ENC.encode(PWD);

    /** BCrypt modular-crypt format 的全部已知 minor 版本前缀。 */
    private static final String[] KNOWN_VARIANTS = {"$2a$", "$2b$", "$2x$", "$2y$"};

    /** 把基准哈希的版本前缀换成指定前缀，得到该变体的等价哈希。 */
    private static String variant(String prefix) {
        return prefix + BASE.substring(4);
    }

    /** 安全地尝试校验：Spring 对非法格式抛 IllegalArgumentException，此处一律视为「校验不通过」。 */
    private static boolean safeMatches(String raw, String hash) {
        try {
            return ENC.matches(raw, hash);
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 组 A：不变式 I —— 推导式（ADR-002）
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("组A-1 凡 matches 能校验通过的变体前缀，isBCrypt 必须放行（推导式）")
    void isBcryptMustNotBeStricterThanMatches() {
        int accepted = 0;
        for (char minor : "abcdefxyz0123456789".toCharArray()) {
            String hash = variant("$2" + minor + "$");
            if (!safeMatches(PWD, hash)) {
                continue;
            }
            accepted++;
            assertTrue(PasswordEncoder.isBCrypt(hash),
                    "matches 能校验 $2" + minor + "$ 开头的哈希，isBCrypt 却判 false —— "
                            + "格式判定比校验更严，会导致该哈希走 MD5 分支并被二次加密");
        }
        // 防「无判别力」：若一个变体都探不到，本用例会空转通过，那不是通过而是环境失效。
        assertTrue(accepted > 0,
                "探针未发现任何 Spring 可校验的变体前缀 —— 测试空转，判定为环境问题而非通过");
    }

    @Test
    @DisplayName("组A-2 无 minor 版本号的 $2$ 不被 matches 接受，故不要求 isBCrypt 放行")
    void dollar2WithoutMinorIsNotABcryptVariant() {
        String hash = "$2$" + BASE.substring(4);
        assertFalse(safeMatches(PWD, hash), "前提不成立：Spring 竟然接受了无 minor 的 $2$");
        // 即便如此，isBCrypt 判 false 仍是正确方向（不放行 → 走 MD5 分支 → 校验失败 → 不改数据）
        assertFalse(PasswordEncoder.isBCrypt(hash));
    }

    @Test
    @DisplayName("组A-3 大写 minor（$2A$）不被 matches 接受，isBCrypt 不放行")
    void uppercaseMinorIsRejected() {
        String hash = "$2A$" + BASE.substring(4);
        assertFalse(safeMatches(PWD, hash), "前提不成立：Spring 竟然接受了大写 minor");
        assertFalse(PasswordEncoder.isBCrypt(hash));
    }

    // ─────────────────────────────────────────────────────────────
    // 组 B：不得放宽过头
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("组B-1 MD5 哈希（32 位十六进制）判为非 BCrypt")
    void md5HashIsNotBcrypt() {
        String md5 = StringTools.encodeByMD5(PWD);
        assertEquals(32, md5.length(), "前提不成立：MD5 摘要长度变了");
        assertFalse(PasswordEncoder.isBCrypt(md5));
    }

    @Test
    @DisplayName("组B-2 其它非 BCrypt 形态一律判 false")
    void nonBcryptShapesRejected() {
        String[] rejected = {
                "$1$" + BASE.substring(4),                    // MD5-crypt，非 bcrypt
                "$20$" + BASE.substring(4),                   // minor 是数字
                "$2c$" + BASE.substring(4),                   // 未定义的 minor
                "$2z$" + BASE.substring(4),
                "2a$10$nosign",                                // 缺 $
                "$2a$",                                       // 截断
                "$2a$10$tooshort",                            // 截断
                "secret123",                                   // 明文
                "0123456789abcdef0123456789abcdef",            // 32 位但非 MD5 形态的裸 hex
        };
        for (String s : rejected) {
            assertFalse(PasswordEncoder.isBCrypt(s), "不应被判定为 BCrypt：" + s);
        }
    }

    @Test
    @DisplayName("组B-3 空值与空串判 false")
    void nullAndEmptyRejected() {
        assertFalse(PasswordEncoder.isBCrypt(null));
        assertFalse(PasswordEncoder.isBCrypt(""));
        assertFalse(PasswordEncoder.isBCrypt("   "));
    }

    // ─────────────────────────────────────────────────────────────
    // 组 C：既有语义不回归
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("组C-1 encode 产出 60 字符 BCrypt 哈希，且两次编码结果不同（内置盐）")
    void encodeProducesBcryptHash() {
        String h1 = PasswordEncoder.encode(PWD);
        String h2 = PasswordEncoder.encode(PWD);
        assertEquals(60, h1.length());
        assertTrue(h1.startsWith("$2"), "哈希应以 BCrypt 版本前缀开头，实际：" + h1.substring(0, 7));
        assertTrue(PasswordEncoder.isBCrypt(h1), "自家 encode 的产物必须被自家 isBCrypt 认作 BCrypt");
        assertNotEquals(h1, h2, "BCrypt 内置随机盐，两次编码不应相同");
    }

    @Test
    @DisplayName("组C-2 matches 正确密码为 true、错误密码为 false")
    void matchesBehavesCorrectly() {
        String h = PasswordEncoder.encode(PWD);
        assertTrue(PasswordEncoder.matches(PWD, h));
        assertFalse(PasswordEncoder.matches("wrong-password", h));
    }

    @Test
    @DisplayName("组C-3 encode/matches 空入参守卫")
    void nullGuardsPreserved() {
        assertNull(PasswordEncoder.encode(null));
        assertNull(PasswordEncoder.encode(""));
        assertFalse(PasswordEncoder.matches(null, PasswordEncoder.encode(PWD)));
        assertFalse(PasswordEncoder.matches(PWD, null));
        assertFalse(PasswordEncoder.matches(PWD, ""));
        assertFalse(PasswordEncoder.matches(PWD, "not-a-hash-at-all"));
    }

    // ─────────────────────────────────────────────────────────────
    // 组 D：硬编码侧 —— 发现「多拒」
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("组D-1 已知 BCrypt 变体必须全部放行（推导式断言发现不了多拒，故单列硬编码）")
    void allKnownVariantsAccepted() {
        for (String prefix : KNOWN_VARIANTS) {
            assertTrue(PasswordEncoder.isBCrypt(variant(prefix)),
                    prefix + " 是 BCrypt 已知变体，isBCrypt 必须放行 —— "
                            + "漏放会导致该哈希走 MD5 分支并被二次加密（不可逆损坏）");
        }
    }

    @Test
    @DisplayName("组D-2 全部已知变体都能被 matches 校验（前提自检：变体集合本身有效）")
    void knownVariantsAreActuallyVerifiable() {
        // 至少 $2a$ 必须可校验；若连基准都验不过，说明环境坏了而非实现有问题。
        assertTrue(safeMatches(PWD, variant("$2a$")), "基准哈希校验不通过，环境异常");
    }
}
