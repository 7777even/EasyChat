package com.easychat.websocket;

/**
 * 通话房间判定的**纯逻辑**（无 IO / 无 Redis / 无 Spring）。
 *
 * <p>抽离理由（call-multi-instance ADR-002/003）：房间迁 Redis 后，创建 / 加入 / 状态转移
 * 都要跟 IO 打交道。把<b>判定</b>抽成纯函数，才能在不启 Redis 的情况下覆盖这些路径 ——
 * 否则只能写出「连不上 Redis 就全红」的<b>环境缺失型</b>测试（AGENTS §2.1 第 3 条）。
 *
 * <p>本类只回答「该不该、错在哪」，不做任何读写。
 */
public final class CallRoomCore {

    private CallRoomCore() {
    }

    // ── 成员状态（与 CallService 原常量一致，集中到纯核心便于单测）──
    public static final int STATUS_INVITED = 0;
    public static final int STATUS_ACCEPTED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int STATUS_BUSY = 3;
    public static final int STATUS_LEFT = 4;

    /**
     * 房间 key 的 TTL。
     *
     * <p>⚠ 必须存在且为有限值 —— 房间 key **不得**是永久 key，
     * 否则实例宕机 / 广播丢失后残留房间无人回收（ADR-002）。
     * 24h 的依据：`VOICE_MAX_DURATION_SECONDS = 60`，
     * 但人可能「接听后挂着不走」，故取一个远大于通话时长的上界。
     */
    public static final int ROOM_TTL_SECONDS = 24 * 3600;

    /** 判定结果。{@code ok=false} 时 {@code errorCode} 为 WS 信令错误码（非 HTTP）。 */
    public record Verdict(boolean ok, int errorCode, String message) {

        static Verdict allow() {
            return new Verdict(true, 0, "");
        }

        static Verdict deny(int errorCode, String message) {
            return new Verdict(false, errorCode, message == null ? "" : message);
        }
    }

    /**
     * 创建房间前的判定。
     *
     * @param callIdAlreadyTaken    Redis 中是否已存在该 callId（ADR-003 冲突检测）
     * @param maxParticipants       群呼人数上限（单聊传任意正值即可，因为 requested 恒 1）
     * @param requestedMemberCount  本端要邀请的成员数（群呼为群成员数）
     * @return ok 才允许建房
     */
    public static Verdict assessCreate(boolean callIdAlreadyTaken, int maxParticipants,
            int requestedMemberCount) {
        if (callIdAlreadyTaken) {
            // 绝不静默把两个不同通话合并进同一房间
            return Verdict.deny(2801,
                    "callId 已被占用，拒绝创建（避免两个通话被合并进同一房间）");
        }
        if (requestedMemberCount > maxParticipants) {
            return Verdict.deny(2801,
                    "成员数超过通话上限(" + maxParticipants + ")");
        }
        return Verdict.allow();
    }

    /**
     * 中途加入的人数判定。
     *
     * <p>⚠ 判定必须是 {@code >=} 放行、{@code >} 拒绝的**边界方向**：
     * 已 {@code max-1} 人时来第 {@code max} 人应当放行。
     */
    public static Verdict assessJoin(int currentAcceptedCount, int maxParticipants) {
        if (currentAcceptedCount >= maxParticipants) {
            return Verdict.deny(2801, "通话人数已达上限(" + maxParticipants + ")");
        }
        return Verdict.allow();
    }

    /** 终态：到达后不得再被改写（否则挂断后延迟的 ACCEPT 会把房间拉回通话中）。 */
    public static boolean isTerminal(int status) {
        return status == STATUS_REJECTED || status == STATUS_BUSY || status == STATUS_LEFT;
    }

    /**
     * 成员状态转移。终态不可逆。
     *
     * @param from 当前状态
     * @param to   期望转到的新状态
     * @return 实际应生效的状态（终态时返回原状态）
     */
    public static int transition(int from, int to) {
        if (isTerminal(from)) {
            return from;
        }
        return to;
    }
}
