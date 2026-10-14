package com.easychat.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 通话房间判定的纯逻辑测试（call-multi-instance T1）。
 *
 * <p>为何抽纯核心：房间迁 Redis 后，创建/加入/状态转移全部要跟 IO 打交道。
 * 把<b>判定</b>抽成无 IO 的纯函数，才能在不启 Redis 的情况下覆盖这些路径 ——
 * 否则只能写出「连不上 Redis 就全红」的<b>环境缺失型</b>测试（AGENTS §2.1 第 3 条）。
 *
 * <p>⚠ 本类是 JUnit 4：断言消息在前、条件在后（{@code assertTrue(String, boolean)}）。
 */
public class CallRoomCoreTest {

    // ── callId 冲突（ADR-003：拒绝而非静默合并）──────────────────────

    @Test
    public void creatingRoomWithTakenCallIdIsRejected() {
        CallRoomCore.Verdict v = CallRoomCore.assessCreate(true, 6, 1);
        assertFalse("callId 已被占用时必须拒绝", v.ok());
        assertTrue("必须给出错误码", v.errorCode() > 0);
    }

    @Test
    public void creatingRoomWithFreeCallIdIsAllowed() {
        assertTrue(CallRoomCore.assessCreate(false, 6, 1).ok());
    }

    @Test
    public void conflictVerdictCarriesActionableMessage() {
        CallRoomCore.Verdict v = CallRoomCore.assessCreate(true, 6, 1);
        assertTrue("文案须点出根因：" + v.message(), v.message().contains("callId"));
    }

    // ── 人数上限 ───────────────────────────────────────────────────────

    @Test
    public void groupInviteOverCapIsRejected() {
        CallRoomCore.Verdict v = CallRoomCore.assessCreate(false, 6, 7);
        assertFalse("群成员超上限必须在建房前拒绝", v.ok());
        assertTrue("文案须点明是人数上限：" + v.message(), v.message().contains("上限"));
    }

    @Test
    public void groupInviteExactlyAtCapIsAllowed() {
        assertTrue("恰好等于上限应放行（判定须是 > 而非 >=）",
                CallRoomCore.assessCreate(false, 6, 6).ok());
    }

    @Test
    public void joinOverCapIsRejected() {
        assertFalse(CallRoomCore.assessJoin(6, 6).ok());
    }

    @Test
    public void joinExactlyAtCapIsAllowed() {
        assertTrue("已 5 人时来第 6 人应放行", CallRoomCore.assessJoin(5, 6).ok());
    }

    /** 反向制衡：若上限判定恒真，群呼会无限膨胀而无人察觉 */
    @Test
    public void capVerdictDistinguishesOverAndUnder() {
        assertNotEquals(CallRoomCore.assessCreate(false, 3, 4).ok(),
                CallRoomCore.assessCreate(false, 3, 3).ok());
    }

    // ── 成员状态转移（终态不可逆）────────────────────────────────────

    @Test
    public void transitionAllowed() {
        assertEquals("INVITED 之后可以是 ACCEPTED", CallRoomCore.STATUS_ACCEPTED,
                CallRoomCore.transition(CallRoomCore.STATUS_INVITED, CallRoomCore.STATUS_ACCEPTED));
    }

    @Test
    public void transitionFromTerminalIsIgnored() {
        for (int s : new int[] { CallRoomCore.STATUS_REJECTED,
                CallRoomCore.STATUS_BUSY, CallRoomCore.STATUS_LEFT }) {
            assertEquals("终态 " + s + " 不得被改写为 ACCEPTED", s,
                    CallRoomCore.transition(s, CallRoomCore.STATUS_ACCEPTED));
        }
    }

    @Test
    public void terminalStatusSet() {
        for (int s : new int[] { CallRoomCore.STATUS_REJECTED,
                CallRoomCore.STATUS_BUSY, CallRoomCore.STATUS_LEFT }) {
            assertTrue("状态 " + s + " 必须是终态", CallRoomCore.isTerminal(s));
        }
        assertFalse("INVITED 不是终态（还应能被应答）", CallRoomCore.isTerminal(CallRoomCore.STATUS_INVITED));
        assertFalse("ACCEPTED 不是终态（通话中还可能挂断）",
                CallRoomCore.isTerminal(CallRoomCore.STATUS_ACCEPTED));
    }

    /** 反向制衡：若 isTerminal 恒真，所有房间都会「锁死」不再接受任何状态变更 */
    @Test
    public void terminalNotAlwaysTrue() {
        assertFalse(CallRoomCore.isTerminal(CallRoomCore.STATUS_INVITED));
    }

    // ── 房间 TTL（ADR-002：必须带 TTL，禁止永久 key）──────────────────

    @Test
    public void roomTtlIsPositiveAndBounded() {
        assertTrue("房间 key 必须带 TTL（异常残留需能自动回收）",
                CallRoomCore.ROOM_TTL_SECONDS > 0);
        assertTrue("TTL 不应超过一天（否则残留过久）",
                CallRoomCore.ROOM_TTL_SECONDS <= 24 * 3600);
    }
}
