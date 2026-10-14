package com.easychat.websocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 通话房间的 Redis 存储（call-multi-instance 层二 / ADR-002）。
 *
 * <p><b>解决什么问题</b>：{@code CallService.rooms} / {@code userCall} 是进程内
 * {@code ConcurrentHashMap}，多实例下创建房间的实例一重启/漂移，房间就丢，
 * 其余参与者悬挂到超时。
 *
 * <p><b>为何用 Hash + 成员子 Map 而非整房 JSON</b>：成员状态（接听/挂断/加入）是高频
 * <b>字段级</b>写。整房 JSON 需要 read-modify-write，并发下会互相覆盖（lost update）。
 *
 * <p>⚠ 本类只做 IO；<b>判定</b>全部委托 {@link CallRoomCore}（纯逻辑），
 * 使「该不该」与「怎么存」分离。
 */
@Component("callRoomStore")
public class CallRoomStore {

    private static final String ROOM_KEY_PREFIX = "call:room:";
    private static final String MEMBER_KEY_SUFFIX = ":members";
    /** 用户 → callId 反查表 */
    private static final String USER_CALL_KEY = "call:user";

    /** 与 {@link CallRoomCore#ROOM_TTL_SECONDS} 同源：房间残留不应超过一天 */
    private static final long TTL_SECONDS = CallRoomCore.ROOM_TTL_SECONDS;

    @Resource
    private RedissonClient redissonClient;

    public CallRoomStore() {
    }

    /** 测试用构造器（免 Spring 容器）。 */
    public CallRoomStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 房间快照（扁平字段，便于逐字段 HSET；不用嵌套结构以免整房序列化）。 */
    public static class CallRoomSnapshot {
        private String callId;
        private String callerId;
        private int callType;
        private String groupId;
        private int mediaType;
        private String iceServersJson;

        public CallRoomSnapshot() {
        }

        public CallRoomSnapshot(String callId, String callerId, int callType,
                String groupId, int mediaType, String iceServersJson) {
            this.callId = callId;
            this.callerId = callerId;
            this.callType = callType;
            this.groupId = groupId;
            this.mediaType = mediaType;
            this.iceServersJson = iceServersJson;
        }

        public String getCallId() {
            return callId;
        }

        public void setCallId(String callId) {
            this.callId = callId;
        }

        public String getCallerId() {
            return callerId;
        }

        public void setCallerId(String callerId) {
            this.callerId = callerId;
        }

        public int getCallType() {
            return callType;
        }

        public void setCallType(int callType) {
            this.callType = callType;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public int getMediaType() {
            return mediaType;
        }

        public void setMediaType(int mediaType) {
            this.mediaType = mediaType;
        }

        public String getIceServersJson() {
            return iceServersJson;
        }

        public void setIceServersJson(String iceServersJson) {
            this.iceServersJson = iceServersJson;
        }
    }

    private static String roomKey(String callId) {
        return ROOM_KEY_PREFIX + callId;
    }

    private static String memberKey(String callId) {
        return roomKey(callId) + MEMBER_KEY_SUFFIX;
    }

    private RMap<String, String> roomMap(String callId) {
        return redissonClient.<String, String>getMap(roomKey(callId));
    }

    private RMap<String, String> memberMap(String callId) {
        return redissonClient.<String, String>getMap(memberKey(callId));
    }

    // ── 房间 ─────────────────────────────────────────────────────────

    /**
     * 创建房间。
     *
     * <p>⚠ 用 {@code putIfAbsent}（HSETNX）保证<b>原子性</b>：两个实例同时收到同一
     * callId 的 invite 时只有一个会成功，另一个拿到非 null 返回值 → 由上层拒绝。
     * 若改用普通 {@code put}，后到的会<b>覆盖</b>先到的，两个不同通话被合并进同一房间。
     *
     * @return true = 创建成功；false = callId 已被占用
     */
    public boolean createRoom(CallRoomSnapshot snapshot) {
        RMap<String, String> map = roomMap(snapshot.getCallId());
        String old = map.putIfAbsent("callerId", snapshot.getCallerId());
        if (old != null) {
            return false;
        }
        map.put("callType", String.valueOf(snapshot.getCallType()));
        map.put("groupId", snapshot.getGroupId() == null ? "" : snapshot.getGroupId());
        map.put("mediaType", String.valueOf(snapshot.getMediaType()));
        map.put("iceServersJson", snapshot.getIceServersJson() == null ? "" : snapshot.getIceServersJson());
        map.expire(TTL_SECONDS, TimeUnit.SECONDS);
        memberMap(snapshot.getCallId()).expire(TTL_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    /** 房间存在性（HSETNX 之外的辅助探测）。 */
    public boolean exists(String callId) {
        return roomMap(callId).get("callerId") != null;
    }

    public CallRoomSnapshot getRoom(String callId) {
        RMap<String, String> map = roomMap(callId);
        String callerId = map.get("callerId");
        if (callerId == null) {
            return null;
        }
        CallRoomSnapshot s = new CallRoomSnapshot();
        s.setCallId(callId);
        s.setCallerId(callerId);
        s.setCallType(parseInt(map.get("callType")));
        s.setGroupId(map.get("groupId"));
        s.setMediaType(parseInt(map.get("mediaType")));
        s.setIceServersJson(map.get("iceServersJson"));
        return s;
    }

    /** 刷新房间 TTL（长通话续期）。 */
    public void touch(String callId) {
        roomMap(callId).expire(TTL_SECONDS, TimeUnit.SECONDS);
        memberMap(callId).expire(TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ── 成员 ─────────────────────────────────────────────────────────

    /** 字段级写：只动一条成员记录，不整房读写。 */
    public void putMember(String callId, String userId, int status) {
        memberMap(callId).put(userId, String.valueOf(status));
    }

    /** 读全部成员（userId → 状态）。损坏条目回落为 INVITED 而非抛异常。 */
    public Map<String, Integer> members(String callId) {
        RMap<String, String> map = memberMap(callId);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            out.put(e.getKey(), parseInt(e.getValue()));
        }
        return out;
    }

    public void removeMember(String callId, String userId) {
        memberMap(callId).remove(userId);
    }

    // ── 用户反查 ─────────────────────────────────────────────────────

    public void bindUser(String userId, String callId) {
        RMap<String, String> map = redissonClient.<String, String>getMap(USER_CALL_KEY);
        map.put(userId, callId);
        map.expire(TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 用户当前所在通话；不在通话返回 null。 */
    public String callIdOf(String userId) {
        return redissonClient.<String, String>getMap(USER_CALL_KEY).get(userId);
    }

    public void unbindUser(String userId) {
        redissonClient.<String, String>getMap(USER_CALL_KEY).remove(userId);
    }

    // ── 清理 ─────────────────────────────────────────────────────────

    /**
     * 清理整房：房间 + 成员 + 反查索引。
     *
     * <p>反查索引由调用方按需移除（一个用户只绑一个 callId，故在 {@link #unbindUser}）。
     */
    public void evict(String callId) {
        roomMap(callId).delete();
        memberMap(callId).delete();
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return CallRoomCore.STATUS_INVITED;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return CallRoomCore.STATUS_INVITED;
        }
    }
}
