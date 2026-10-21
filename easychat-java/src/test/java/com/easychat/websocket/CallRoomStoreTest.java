package com.easychat.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

/**
 * 通话房间 Redis 存储的单元测试（call-multi-instance T5）。
 *
 * <p>用 mock 的 {@link RedissonClient} 验证**操作语义**（不需要真 Redis）：
 * <ol>
 *   <li>建房用 {@code putIfAbsent}（HSETNX）—— 这是 callId 冲突能<b>原子</b>检测的唯一依据</li>
 *   <li>房间 key 与成员 key 都带 TTL（禁止永久 key，否则残留无人回收）</li>
 *   <li>成员写入是**字段级**而非整房 JSON（否则并发 lost update）</li>
 *   <li>清理同时移除房间 / 成员 / 用户反查三项</li>
 * </ol>
 */
public class CallRoomStoreTest {

    private RedissonClient redisson;
    private CallRoomStore store;

    /** 房间主表（call:room:<callId>）—— raw 类型，见 {@link #setUp()} 的说明 */
    @SuppressWarnings("rawtypes")
    private RMap roomMap;
    /** 成员子表（call:room:<callId>:members） */
    @SuppressWarnings("rawtypes")
    private RMap memberMap;
    /** 用户反查表（call:user） */
    @SuppressWarnings("rawtypes")
    private RMap userMap;

    @Before
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void setUp() {
        redisson = org.mockito.Mockito.mock(RedissonClient.class);
        // ⚠ 用 **raw** RMap：RedissonClient#getMap 的未绑定泛型返回 RMap（非 RMap<String,String>），
        //   直接 thenReturn(RMap<String,String>) 会因类型不匹配编不过。
        roomMap = org.mockito.Mockito.mock(RMap.class);
        memberMap = org.mockito.Mockito.mock(RMap.class);
        userMap = org.mockito.Mockito.mock(RMap.class);

        when(redisson.getMap("call:room:c1")).thenReturn(roomMap);
        when(redisson.getMap("call:room:c1:members")).thenReturn(memberMap);
        when(redisson.getMap("call:user")).thenReturn(userMap);

        store = new CallRoomStore(redisson);
    }

    private CallRoomStore.CallRoomSnapshot snapshot() {
        // startTime 用固定值，便于断言；anyAccepted 建房时为 false
        return new CallRoomStore.CallRoomSnapshot("c1", "U-caller", 2, "G1", 2, "[]",
                false, 1757650000000L);
    }

    // ── 1. 建房必须原子（HSETNX）──────────────────────────────────────

    @Test
    public void createRoomSucceedsWhenCallIdFree() {
        when(roomMap.putIfAbsent(any(), any())).thenReturn(null);

        assertTrue(store.createRoom(snapshot()));
    }

    /**
     * 建房必须用 {@code putIfAbsent}（HSETNX）而不是普通 {@code put}。
     *
     * <p>⚠ 初版还断言了「房间表 never().put()」—— **那是错的对象**：
     * 房间本身就是 Hash，建房过程必然要逐字段 put（callType/groupId/…）；
     * 真正该断言的是<b>成员写不进房间表</b>（成员有自己的子 Map），
     * 否则「字段级写、避免 lost update」的说法就不成立。
     */
    @Test
    public void createRoomUsesPutIfAbsentForCallerId() {
        when(roomMap.putIfAbsent(any(), any())).thenReturn(null);
        store.createRoom(snapshot());

        verify(roomMap, times(1)).putIfAbsent(eq("callerId"), any());
    }

    @Test
    public void memberWriteNeverTouchesRoomMap() {
        store.putMember("c1", "U2", CallRoomCore.STATUS_ACCEPTED);

        verify(memberMap).put(eq("U2"), any());
        verify(roomMap, never()).put(any(), any());
    }

    @Test
    public void createRoomFailsWhenCallIdAlreadyTaken() {
        // 已存在 → putIfAbsent 返回旧值（非 null）
        when(roomMap.putIfAbsent(any(), any())).thenReturn("existing");

        assertFalse("callId 已占用必须返回 false，让上层拒绝而不是覆盖",
                store.createRoom(snapshot()));
    }

    // ── 2. TTL：禁止永久 key ─────────────────────────────────────────

    @Test
    public void createRoomSetsTtl() {
        when(roomMap.putIfAbsent(any(), any())).thenReturn(null);
        store.createRoom(snapshot());

        verify(roomMap).expire(anyLong(), eq(TimeUnit.SECONDS));
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(roomMap).expire(ttl.capture(), eq(TimeUnit.SECONDS));
        assertTrue("房间 key 必须带有限 TTL", ttl.getValue() > 0);
        assertTrue("TTL 不得为负/零", ttl.getValue() <= 24 * 3600L);
    }

    @Test
    public void bindUserAlsoExpires() {
        store.bindUser("U1", "c1");
        verify(userMap).expire(anyLong(), eq(TimeUnit.SECONDS));
    }

    // ── 3. 成员写为字段级 ─────────────────────────────────────────────

    @Test
    public void putMemberWritesFieldNotWholeRoomJson() {
        store.putMember("c1", "U2", CallRoomCore.STATUS_ACCEPTED);

        verify(memberMap).put(eq("U2"), any());
        verify(roomMap, never()).put(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void membersReadBack() {
        // members() 走 entrySet()，故 stub entrySet 而非 get
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("U2", String.valueOf(CallRoomCore.STATUS_ACCEPTED));
        when(memberMap.entrySet()).thenReturn(raw.entrySet());

        Map<String, Integer> m = store.members("c1");
        assertEquals(1, m.size());
        assertEquals(CallRoomCore.STATUS_ACCEPTED, (int) m.get("U2"));
    }

    /** 垃圾值不得让整个读取崩 */
    @Test
    @SuppressWarnings("unchecked")
    public void membersTolerateCorruptEntry() {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("U2", "not-a-number");
        when(memberMap.entrySet()).thenReturn(raw.entrySet());

        Map<String, Integer> m = store.members("c1");
        assertEquals(1, m.size());
        assertEquals("无法解析时回落为 INVITED", CallRoomCore.STATUS_INVITED, (int) m.get("U2"));
    }

    // ── 4. 清理要干净 ────────────────────────────────────────────────

    /**
     * ⚠ 测试名与行为对齐（初版写成 removesRoomMembersAndUserIndex 是**错的**）：
     * {@code evict} 只删房间与成员两张表，**不动**用户反查表 ——
     * 因为一个用户只绑一个 callId，反查的移除是独立动作（{@code unbindUser}）。
     * 把两件事混在一个方法里会让调用方误以为「evict 就够了」而漏清索引。
     */
    @Test
    public void evictRemovesRoomAndMembersOnly() {
        store.bindUser("U-caller", "c1");
        store.evict("c1");

        verify(roomMap).delete();
        verify(memberMap).delete();
        verify(userMap, never()).remove(any());
    }

    @Test
    public void unbindUserRemovesReverseIndex() {
        store.bindUser("U-caller", "c1");
        store.unbindUser("U-caller");
        verify(userMap).remove("U-caller");
    }

    @Test
    public void roomSnapshotRoundTrip() {
        when(roomMap.get("callerId")).thenReturn("U-caller");
        CallRoomStore.CallRoomSnapshot got = store.getRoom("c1");
        assertNotNull(got);
        assertEquals("U-caller", got.getCallerId());
    }

    @Test
    public void getRoomReturnsNullWhenAbsent() {
        when(roomMap.get("callerId")).thenReturn(null);
        assertNull(store.getRoom("c1"));
    }

    // ── 5. 纯判定：成员计数上限 ──────────────────────────────────────

    @Test
    public void joinCapDelegatesToPureCore() {
        // 与 CallRoomCore 同口径：已 6 人再来 1 人应拒绝
        assertFalse(CallRoomCore.assessJoin(6, 6).ok());
        assertTrue(CallRoomCore.assessJoin(5, 6).ok());
    }

    // ── 6. 结束权必须**原子**（2026-10-21 修：重复 call_log）────────────

    /**
     * 🔴 抢占结束权必须**一步完成**。
     *
     * <p><b>缺陷（2026-10-21 实测复现）</b>：原实现是「先 {@code get} 探测房间存在、
     * 再 {@code delete}」，<b>两步非原子</b>。双方<b>同时</b>挂断（分属两实例）时，
     * 两边都探测到「存在」→ 都删成功 → <b>都写一条 call_log</b>。
     * 实测：15 轮「同时挂断」落库 <b>16 行</b>（重复率 ~7%），且重复行的
     * {@code start_time} 完全相同 —— 同一通话被记了两遍。
     *
     * <p>这不是「极端并发」：双方一起挂断是**日常场景**。
     *
     * <p><b>修法</b>：{@code RMap#remove(key, value)} 在 Redisson 里是
     * <b>单条 EVAL</b>（compare-and-delete，原子），于是「房间是否仍属于该通话」
     * 与「抢占结束权」合并为一步 —— 并发双方只有一方拿得到 true。
     */
    @Test
    public void evictRoomClaimsEndRightAtomically() {
        when(roomMap.remove(eq("callerId"), eq("U-caller"))).thenReturn(true);

        assertTrue("抢到结束权必须返回 true（上层据此写 call_log）",
                store.evictRoom("c1", "U-caller"));

        verify(roomMap, times(1)).remove(eq("callerId"), eq("U-caller"));
        // ★ 反向制衡：不得再用「先 get 探测、再 delete」的两步写法 ——
        //   那正是重复 call_log 的根因（两步之间存在窗口）。
        verify(roomMap, never()).get(eq("callerId"));
    }

    /** 已被他人结束 → 必须返回 false，且**不得**删房间（否则会把别人的结束动作重复做一遍）。 */
    @Test
    public void evictRoomReturnsFalseWhenEndRightAlreadyTaken() {
        when(roomMap.remove(eq("callerId"), eq("U-caller"))).thenReturn(false);

        assertFalse("结束权已被抢走必须返回 false —— 否则会多写一条 call_log",
                store.evictRoom("c1", "U-caller"));

        verify(roomMap, never()).delete();
        verify(memberMap, never()).delete();
    }

    /** 房间不属于该通话（callerId 不匹配）→ 不得抢到结束权。 */
    @Test
    public void evictRoomRejectsMismatchedCaller() {
        when(roomMap.remove(eq("callerId"), eq("U-other"))).thenReturn(false);

        assertFalse(store.evictRoom("c1", "U-other"));
        verify(roomMap, never()).delete();
    }

    /** 抢到结束权后，房间与成员两张表都要清掉。 */
    @Test
    public void evictRoomAlsoClearsMembers() {
        when(roomMap.remove(eq("callerId"), eq("U-caller"))).thenReturn(true);

        store.evictRoom("c1", "U-caller");

        verify(roomMap).delete();
        verify(memberMap).delete();
    }
}
