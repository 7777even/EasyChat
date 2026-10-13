package com.easychat.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

/**
 * TURN 配置核心的单元测试（T7 / T8）。
 *
 * <p>抽纯核心的理由：{@code CallService} 是 Spring Bean，直接持有 WS 通道、进程内房间表与
 * {@code call_log} 落库依赖，构造它需要整个容器 —— 那正是本仓反复踩过的
 * 「环境缺失导致恒红」老路（见 {@code openspec/changes/2026-10-12-preflight-baseline-machine-check}）。
 *
 * <p>⚠ <b>本测试必须用 JUnit 4</b>（{@code org.junit.Test}）：本仓 pom 同时引入了
 * {@code junit-vintage-engine} 与 {@code junit-jupiter-engine}，surefire 2.22.2 会选用
 * JUnit4 provider —— <b>JUnit 5 的 {@code @Test} 能编译通过但根本不会被执行</b>。
 * 2026-10-13 初版误用 JUnit5，实测表现为 {@code Tests run: 0 … No tests were executed!}，
 * 若未核对就是一份<b>静默零覆盖</b>的测试。
 *
 * @see TurnCredentialCore
 */
public class TurnCredentialCoreTest {

    // ── 独立对拍实现：与 production 同算法，但在此处重写一遍 ────────────
    // ⚠ 刻意**不复用** TurnCredentialCore.sign —— 否则测试与实现同源，
    //   实现写错算法时测试会跟着错，属最典型的「自证式覆盖」。
    private static String referenceCredential(String secret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 形态判定（T7）─────────────────────────────────────────────────

    @Test
    public void bothEmpty() {
        TurnCredentialCore.Shape s =
                TurnCredentialCore.assess(TurnCredentialCore.Config.of("", "", "", "", "", 0));
        assertEquals("双空必须告警：它表现为「接通后无声」", "warn", s.level());
        assertTrue("告警文案须点明后果：" + s.message(), s.message().contains("对称 NAT"));
    }

    @Test
    public void incomplete() {
        TurnCredentialCore.Shape onlyUrl = TurnCredentialCore.assess(
                TurnCredentialCore.Config.of("", "turn:host:3478", "", "", "", 0));
        assertEquals("warn", onlyUrl.level());
        assertTrue("缺认证字段必须被点名：" + onlyUrl.message(),
                onlyUrl.message().contains("username") || onlyUrl.message().contains("credential"));

        TurnCredentialCore.Shape onlyUser = TurnCredentialCore.assess(
                TurnCredentialCore.Config.of("", "turn:host:3478", "u", "", "", 0));
        assertEquals("warn", onlyUser.level());
        assertTrue("缺 credential 必须被点名：" + onlyUser.message(),
                onlyUser.message().contains("credential"));
    }

    @Test
    public void staticCredential() {
        TurnCredentialCore.Shape s = TurnCredentialCore.assess(
                TurnCredentialCore.Config.of("", "turn:host:3478", "u", "p", "", 0));
        assertEquals("warn", s.level());
        assertTrue("必须点明是长期静态凭据：" + s.message(), s.message().contains("静态"));
    }

    @Test
    public void ephemeralOk() {
        TurnCredentialCore.Shape s = TurnCredentialCore.assess(
                TurnCredentialCore.Config.of("stun:host:3478", "turn:host:3478", "", "",
                        "shared-secret", 3600));
        assertEquals("配全且用时效凭据时不该告警", "info", s.level());
    }

    // ── 凭据签发（T8）─────────────────────────────────────────────────

    @Test
    public void usernameShape() {
        assertEquals("1757650000:U01234567890",
                TurnCredentialCore.buildEphemeralUsername(1757650000L, "U01234567890"));
    }

    @Test
    public void matchesCoturnDefinition() {
        String secret = "s3cr3t";
        String username = TurnCredentialCore.buildEphemeralUsername(1757650000L, "U1");
        assertEquals("算法必须与 coturn 官方 README 的定义逐字一致，否则 coturn 必然拒签",
                referenceCredential(secret, username), TurnCredentialCore.sign(secret, username));
    }

    @Test
    public void base64Standard() {
        String c = TurnCredentialCore.sign("secret", "1757650000:U1");
        assertTrue("非标准 base64 会被 coturn 拒绝：" + c, c.matches("^[A-Za-z0-9+/]+={0,2}$"));
        assertEquals("HMAC-SHA1 输出应为 20 字节", 20, Base64.getDecoder().decode(c).length);
    }

    @Test
    public void multiSecretRotation() {
        String oldS = "old-secret";
        String newS = "new-secret";
        List<String> secrets = TurnCredentialCore.parseSecrets(oldS + "," + newS);
        assertEquals(2, secrets.size());
        String username = TurnCredentialCore.buildEphemeralUsername(1757650000L, "U1");
        for (String s : secrets) {
            assertEquals(referenceCredential(s, username), TurnCredentialCore.sign(s, username));
        }
        assertNotEquals("不同密钥必须签出不同凭据，否则「轮换」无从谈起",
                TurnCredentialCore.sign(oldS, username), TurnCredentialCore.sign(newS, username));
    }

    @Test
    public void parseSecretsTrimsBlanks() {
        assertEquals(2, TurnCredentialCore.parseSecrets("a,,b,").size());
        assertEquals(0, TurnCredentialCore.parseSecrets(" , , ").size());
    }

    // ── iceServers 拼装（C4）──────────────────────────────────────────

    @Test
    public void noStunEntryWhenBlank() {
        assertEquals("STUN 必须由配置决定，前端不得再兜底公共地址",
                0, TurnCredentialCore.stunUrls("").size());
    }

    @Test
    public void stunEntryFromConfig() {
        assertEquals(List.of("stun:host:3478"), TurnCredentialCore.stunUrls("  stun:host:3478  "));
    }

    @Test
    public void noTurnEntryWhenBlank() {
        assertEquals(0, TurnCredentialCore.turnUrls("").size());
    }
}
