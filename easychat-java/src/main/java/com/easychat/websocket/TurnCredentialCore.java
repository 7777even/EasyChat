package com.easychat.websocket;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TURN / STUN 配置的纯判定核心（**无状态、无 IO、无 Spring**）。
 *
 * <p>抽离理由：{@code CallService} 是 Spring Bean，直接持有 WS 通道、进程内房间表与
 * {@code call_log} 落库依赖。直接测它需要整个容器 —— 那正是本仓反复踩过的
 * 「环境缺失导致恒红」老路（见 {@code openspec/changes/2026-10-12-preflight-baseline-machine-check}）。
 * 故把「判配置形态 / 签发时效凭据 / 拼条目」三件事全部落到本类，
 * {@code CallService} 只做 IO 接线。
 *
 * <p><b>不依赖 fastjson</b>：本类只用 JDK 的 {@code List}，便于脱离容器断言；
 * {@code CallService} 负责把结果拼成 {@code JSONArray}。
 *
 * <p>时效凭据算法依据 coturn 官方 {@code README.turnserver} 原文：
 * <pre>
 *   temporary-username  = "timestamp" + ":" + "username"
 *   temporary-password  = base64_encode(hmac-sha1(shared-secret, temporary-username))
 * </pre>
 * 分隔符由 coturn {@code --rest-api-separator} 控制，<b>默认即 {@code :}</b>，故此处不新增配置。
 */
public final class TurnCredentialCore {

    private TurnCredentialCore() {
    }

    /** TURN 配置快照（全部已 trim 或为 null/空串） */
    public record Config(String stunUrl, String turnUrl,
                         String turnUsername, String turnCredential,
                         String staticSecret, int ttlSeconds) {

        /**
         * 工厂：把可能为 null 的配置项规整为空串，避免下游到处判 null。
         *
         * @param ttlSeconds 时效凭据有效期（秒）；&lt;=0 表示不签发时效凭据
         */
        public static Config of(String stunUrl, String turnUrl, String turnUsername,
                                String turnCredential, String staticSecret, int ttlSeconds) {
            return new Config(nz(stunUrl), nz(turnUrl), nz(turnUsername),
                    nz(turnCredential), nz(staticSecret), ttlSeconds);
        }

        private static String nz(String s) {
            return s == null ? "" : s.trim();
        }
    }

    /**
     * 配置形态的诊断结论。
     *
     * @param level   {@code "info"} 或 {@code "warn"} —— 三类降级都是 warn
     * @param message 中文告警文案
     */
    public record Shape(String level, String message) {
    }

    // ── 形态判定（T7）────────────────────────────────────────────────

    /**
     * 判定当前 TURN/STUN 配置的形态，给出应记的日志级别与文案。
     *
     * <p>三类降级都判为 {@code warn}，因为它们的表现都是**用户侧静默失败**
     * （对称 NAT 下「接通后无声」），属必须暴露给运维的事实：
     * <ol>
     *   <li>STUN 与 TURN **双空** —— 无任何 NAT 穿透手段</li>
     *   <li>TURN URL 非空但认证字段不全 —— 下发的条目无法被 coturn 认证</li>
     *   <li>走长期静态凭据 —— 泄漏窗口无上限（ADR-002）</li>
     * </ol>
     * 只有「配全且用时效凭据」才是 {@code info}。
     *
     * @param config 配置快照
     * @return 诊断结论，永不为 null
     */
    public static Shape assess(Config config) {
        boolean hasTurn = !config.turnUrl().isEmpty();
        boolean hasStun = !config.stunUrl().isEmpty();

        if (!hasTurn && !hasStun) {
            return new Shape("warn",
                    "STUN 与 TURN 均未配置：多数 NAT 下通话可用，但**对称 NAT 下将接通后无声**"
                            + "（该现象无报错、无用户提示，是最易被漏掉的故障）。"
                            + "生产请注入 EASYCHAT_TURN_URL + EASYCHAT_TURN_STATIC_SECRET。");
        }
        if (hasTurn && config.turnUsername().isEmpty() && config.turnCredential().isEmpty()
                && (config.staticSecret().isEmpty() || config.ttlSeconds() <= 0)) {
            return new Shape("warn",
                    "已配置 easychat.turn.url 但**没有任何可用凭据**"
                            + "（既无 username/credential，也未配 static-secret 或 ttl<=0），"
                            + "coturn 会拒绝所有中继请求。");
        }
        if (hasTurn && !(config.turnUsername().isEmpty() && config.turnCredential().isEmpty())
                && (config.staticSecret().isEmpty() || config.ttlSeconds() <= 0)) {
            return new Shape("warn",
                    "正在使用**长期静态 TURN 凭据**（username/credential）："
                            + "凭据一旦泄漏即可长期盗用中继带宽，泄漏窗口无上限。"
                            + "建议改配 easychat.turn.static-secret 启用时效凭据。");
        }
        return new Shape("info", "TURN 时效凭据已启用。");
    }

    // ── 凭据签发（T8）─────────────────────────────────────────────────

    /**
     * 生成时效凭据的 username：{@code <unix时间戳>:<用户ID>}。
     *
     * <p>分隔符固定为 {@code :}，与 coturn {@code --rest-api-separator} 的默认值一致
     * （2026-10-13 查官方 README 确认）。若运维改了该默认值，本方法必须同步。
     *
     * @param timestampSeconds 秒级时间戳
     * @param userId           用户 ID（如 {@code U01234567890}）
     * @return {@code timestamp:userId}
     */
    public static String buildEphemeralUsername(long timestampSeconds, String userId) {
        return timestampSeconds + ":" + (userId == null ? "" : userId);
    }

    /**
     * 按 coturn 官方定义签发凭据：{@code base64(HMAC-SHA1(secret, username))}。
     *
     * <p>⚠ {@link Mac} 实例**非线程安全**，而本类是无状态纯函数、被多请求并发调用，
     * 故**每次新建**、不缓存为字段。
     *
     * @param secret   共享密钥（coturn 的 {@code static-auth-secret}）
     * @param username {@link #buildEphemeralUsername} 的产物
     * @return 标准 base64（coturn 按标准字母表解码，非 URL-safe）
     */
    public static String sign(String secret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 理论上不可达：HmacSHA1 是 JDK 必备算法
            throw new IllegalStateException("TURN 凭据签名失败", e);
        }
    }

    /**
     * 解析逗号分隔的多个共享密钥。
     *
     * <p>为何要多个：coturn 官方写明 {@code --static-auth-secret} 支持
     * “Multiple shared secrets can be used”，故轮换期可让**新旧密钥并存**，
     * 换完再摘除旧的 —— <b>无需停机</b>。
     *
     * @param commaSeparated 形如 {@code "old-secret,new-secret"}；空白项被剔除
     * @return 密钥列表，永不为 null
     */
    public static List<String> parseSecrets(String commaSeparated) {
        List<String> out = new ArrayList<>();
        if (commaSeparated == null || commaSeparated.isEmpty()) {
            return out;
        }
        for (String part : commaSeparated.split(",")) {
            String s = part.trim();
            // ⚠ 必须剔除空项：拿 "" 去签名会产出一个「看起来合法但 coturn 必然拒绝」的凭据
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ── iceServers 条目（C4）──────────────────────────────────────────

    /**
     * STUN 条目：<b>由配置决定，留空则不产出</b>。
     *
     * <p>刻意不做「留空就回落公共 STUN」—— 前端原本硬编码了一份
     * {@code stun.l.google.com}，两处各持一份必然漂移，且依赖境外公共服务。
     *
     * @param stunUrl 已 trim 的 STUN 地址，可为空串
     * @return 0 或 1 项
     */
    public static List<String> stunUrls(String stunUrl) {
        return oneIfPresent(stunUrl);
    }

    /**
     * TURN 条目：同 {@link #stunUrls}，留空不产出。
     *
     * @param turnUrl 已 trim 的 TURN 地址，可为空串
     * @return 0 或 1 项
     */
    public static List<String> turnUrls(String turnUrl) {
        return oneIfPresent(turnUrl);
    }

    private static List<String> oneIfPresent(String url) {
        List<String> out = new ArrayList<>();
        if (url != null && !url.trim().isEmpty()) {
            out.add(url.trim());
        }
        return out;
    }
}
