package com.easychat.websocket;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.CallLog;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.service.CallLogService;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 语音/视频通话信令中继服务。
 * <p>
 * 设计要点（对齐 design.md ADR）：
 * - 媒体走 WebRTC P2P（full-mesh），服务器**不碰媒体**，仅按房间注册表转发信令帧。
 * - 复用现有 Netty WS 连接（5051）与 {@link ChannelContextUtils#USER_CONTEXT_MAP} 会话映射，不新增端口/HTTP 接口。
 * - TURN 配置由服务端随信令引导帧下发（{@code iceServers}），前端源码不含凭据。
 * - 通话结束（任一方式）落 {@code call_log}。
 * <p>
 * <b>⚠️ 单实例约束（已知限制，勿用 --scale 横向扩后端）</b><br>
 * 本类的 {@code rooms} / {@code userCall} 是进程内 {@link ConcurrentHashMap}，
 * {@link ChannelContextUtils#USER_CONTEXT_MAP} 同理。信令能跨实例是因为广播走 Redisson RTopic，
 * 但**通话房间注册表不跨实例**：一旦后端跑多份，A 用户与 B 用户被负载均衡打到不同实例时，
 * 呼叫/接听信令将找不到对方，通话直接建立失败。
 * 修复方向：房间注册表迁 Redis（与离线缓冲同一套 Redis 基础设施）。当前部署形态为单实例，见
 * {@code docs/system-facts.md} §1.1 与仓库根 {@code docker-compose.yml}。
 */
@Component("callService")
public class CallService {

    private static final Logger logger = LoggerFactory.getLogger(CallService.class);

    @Resource
    private ChannelContextUtils channelContextUtils;
    @Resource
    private UserContactService userContactService;
    @Resource
    private GroupInfoService groupInfoService;
    @Resource
    private CallLogService callLogService;

    @Value("${easychat.turn.url:}")
    private String turnUrl;
    @Value("${easychat.turn.username:}")
    private String turnUsername;
    @Value("${easychat.turn.credential:}")
    private String turnCredential;
    /**
     * STUN 地址（ADR-003）。**刻意无默认值**：原先前后端各硬编码一份
     * {@code stun.l.google.com:19302}，双份维护必然漂移，且依赖境外公共服务。
     * 留空即不下发 STUN 条目，由服务端统一决定。
     */
    @Value("${easychat.turn.stun-url:}")
    private String turnStunUrl;
    /**
     * 共享密钥（coturn 的 {@code static-auth-secret}，ADR-001）。
     * 非空即启用**时效凭据**；留空则回落上面的静态 username/credential（并告警）。
     * 支持**逗号分隔的多密钥**，用于轮换期新旧并存、无需停机。
     */
    @Value("${easychat.turn.static-secret:}")
    private String turnStaticSecret;
    /** 时效凭据有效期（秒）。<=0 表示不签发时效凭据，回落静态模式。 */
    @Value("${easychat.turn.ttl-seconds:3600}")
    private int turnTtlSeconds;
    @Value("${easychat.call.max-participants:6}")
    private int maxParticipants;

    /**
     * 通话信令广播通道（2026-10-14，call-multi-instance 层一）。
     *
     * <p>⚠ 用 {@code @Resource(required = false)} 而非强注入：单测可用
     * {@code standaloneSetup}/纯 Mockito 构造本类而不必提供该依赖；
     * 缺省时 {@link #sendTo} 自动退回本地直投（旧行为）。
     */
    @Resource
    private CallFrameTopic callFrameTopic;

    /**
     * 通话房间存储（2026-10-14，call-multi-instance 层二 / ADR-002）。
     *
     * <p>⚠ 原实现用进程内 {@code ConcurrentHashMap}（rooms / userCall）——
     * 创建房间的实例一重启/被负载均衡漂移，房间即丢，其余参与者悬挂到超时。
     * 现改为 <b>Redis 为唯一真源</b>。
     *
     * <p>⚠ <b>刻意不保留进程内兜底</b>：两份真相比一份真相更危险 ——
     * 它会让「Redis 不可用」表现为<em>静默降级</em>而非明确失败，
     * 而静默降级正是本 Change 要消灭的故障形态（ADR-002）。
     */
    @Resource
    private CallRoomStore callRoomStore;

    /**
     * 启动期自检：把「TURN/STUN 不可用」这一事实**显式打到日志**。
     *
     * <p>为何必要：三者（双空 / 配置不全 / 用静态凭据）的表现**都是用户侧静默失败** ——
     * 对称 NAT 下「接通后无声」，不报错、不提示。此前这些配置缺失在服务端**完全无痕**，
     * 运维无从知晓（2026-10-13 实测确认）。判定逻辑在 {@link TurnCredentialCore}，
     * 本方法只做 IO 接线。
     */
    @PostConstruct
    public void logTurnConfigStatus() {
        TurnCredentialCore.Shape shape = TurnCredentialCore.assess(
                TurnCredentialCore.Config.of(turnStunUrl, turnUrl, turnUsername,
                        turnCredential, turnStaticSecret, turnTtlSeconds));
        if ("warn".equals(shape.level())) {
            logger.warn("[TURN 配置自检] {}", shape.message());
        } else {
            logger.info("[TURN 配置自检] {}", shape.message());
        }
    }

    // ⚠ 2026-10-14（call-multi-instance 层二）：rooms / userCall 两个进程内 Map 已删除。
    //   真源改为 Redis（see callRoomStore）。刻意**不留进程内兜底** ——
    //   两份真相比一份真相更危险：它会把「Redis 不可用」变成静默降级，
    //   而那正是本 Change 要消灭的故障形态（ADR-002）。
    private static final int STATUS_INVITED = 0;
    private static final int STATUS_ACCEPTED = 1;
    private static final int STATUS_REJECTED = 2;
    private static final int STATUS_BUSY = 3;
    private static final int STATUS_LEFT = 4;

    /** 通话记录状态：1已接 2未接 3拒接 4取消 5忙线 */
    private static final int LOG_ACCEPTED = 1;
    private static final int LOG_MISSED = 2;
    private static final int LOG_REJECTED = 3;
    private static final int LOG_CANCELED = 4;
    private static final int LOG_BUSY = 5;
    /** 通话相关错误码（置于信令帧 extendData.code，非 HTTP） */
    private static final int CODE_NOT_FRIEND = 2401;
    private static final int CODE_NOT_IN_GROUP = 2302;
    private static final int CODE_EXCEED_LIMIT = 2801;
    /** callId 已存在于 Redis（ADR-003 拒绝，不静默合并两个通话） */
    private static final int CODE_CALL_ID_CONFLICT = 2802;

    private static class CallMember {
        String userId;
        int status;
        CallMember(String userId, int status) {
            this.userId = userId;
            this.status = status;
        }
    }

    private static class CallRoom {
        String callId;
        String callerId;
        int callType;       // 1 单聊 2 群呼
        String groupId;
        int mediaType;      // 1 音频 2 音视频
        long startTime;
        JSONArray iceServers;
        boolean anyAccepted = false;
        ConcurrentMap<String, CallMember> members = new ConcurrentHashMap<>();
    }

    /**
     * 入口：由 {@code HandlerWebSocket} 在识别到 CALL_* 帧时调用。
     * fromUserId 来自 WS 会话绑定身份，不可由客户端伪造。
     */
    public void handleCallFrame(String fromUserId, JSONObject json) {
        Integer type = json.getInteger("messageType");
        if (type == null) {
            return;
        }
        String callId = json.getString("callId");
        try {
            if (type.equals(Constants.WS_CALL_INVITE)) {
                handleInvite(fromUserId, json);
            } else if (type.equals(Constants.WS_CALL_ACCEPT) || type.equals(Constants.WS_CALL_JOIN)) {
                handleAccept(fromUserId, callId);
            } else if (type.equals(Constants.WS_CALL_REJECT)) {
                handleReject(fromUserId, callId, LOG_REJECTED);
            } else if (type.equals(Constants.WS_CALL_BUSY)) {
                handleReject(fromUserId, callId, LOG_BUSY);
            } else if (type.equals(Constants.WS_CALL_SIGNAL)) {
                handleSignal(fromUserId, json);
            } else if (type.equals(Constants.WS_CALL_HANGUP)) {
                handleHangup(fromUserId, callId);
            } else if (type.equals(Constants.WS_CALL_CANCEL)) {
                handleCancel(fromUserId, callId);
            }
        } catch (Exception e) {
            logger.error("处理通话信令异常 callId={}, type={}", callId, type, e);
        }
    }

    /**
     * 启动期自检：广播开关关闭时**显著告警**。
     *
     * <p>背景（call-multi-instance 层一）：广播是「多实例下通话信令能送达」的唯一手段。
     * 关掉它（紧急止血用）后，若部署形态其实多实例，表现是「拨不通但毫无报错」——
     * 与本次要消灭的故障同类，故必须让它在启动日志里**一眼可见**。
     */
    @PostConstruct
    public void logBroadcastStatus() {
        boolean enabled = callFrameTopic == null || callFrameTopic.isBroadcastEnabled();
        if (enabled) {
            logger.info("[通话] 信令广播已开启（easychat.call.broadcast-signaling=true），多实例可投递");
        } else {
            logger.warn("[通话] 信令广播已关闭（easychat.call.broadcast-signaling=false）："
                    + "通话信令退化为本地直投，**仅单实例部署可用**。"
                    + "若当前是多实例部署，通话将拨不通且无报错 —— 请立即改回 true。");
        }
    }

    /**
     * 连接断开：视为挂断，清理对方浮窗与房间
     */
    public void onUserDisconnect(String userId) {
        // 层二：反查表在 Redis，跨实例断开也能找到其所在通话
        String callId = callRoomStore.callIdOf(userId);
        if (callId == null) {
            return;
        }
        handleHangup(userId, callId);
    }

    private void handleInvite(String fromUserId, JSONObject json) {
        int callType = json.getIntValue("callType");
        int mediaType = json.getIntValue("mediaType");
        String toUserId = json.getString("toUserId");
        String groupId = json.getString("groupId");

        List<String> memberIds = null;
        if (callType == 1) {
            if (fromUserId.equals(toUserId)) {
                sendCallError(fromUserId, null, CODE_NOT_FRIEND, "不能呼叫自己");
                return;
            }
            if (toUserId == null || userContactService.getUserContactByUserIdAndContactId(fromUserId, toUserId) == null) {
                sendCallError(fromUserId, null, CODE_NOT_FRIEND, "对方不是好友");
                return;
            }
        } else {
            if (groupId == null) {
                sendCallError(fromUserId, null, CODE_NOT_IN_GROUP, "群组不存在");
                return;
            }
            try {
                groupInfoService.checkGroupRole(fromUserId, groupId, GroupMemberRoleEnum.MEMBER);
            } catch (Exception e) {
                sendCallError(fromUserId, null, CODE_NOT_IN_GROUP, "不在群组中");
                return;
            }
            memberIds = groupMemberIds(groupId);
            if (memberIds != null && memberIds.size() > maxParticipants) {
                sendCallError(fromUserId, null, CODE_EXCEED_LIMIT,
                        "群成员超过通话上限(" + maxParticipants + ")");
                return;
            }
        }

        // callId 优先复用客户端（发起方）传入的值，保证发起方能拿到自己的 callId 用于后续信令；
        // 客户端未传时服务端生成，向后兼容。
        String callId = json.getString("callId");
        if (callId == null || callId.trim().isEmpty()) {
            callId = java.util.UUID.randomUUID().toString();
        }
        CallRoom room = new CallRoom();
        room.callId = callId;
        room.callerId = fromUserId;
        room.callType = callType;
        room.groupId = groupId;
        room.mediaType = mediaType;
        room.startTime = System.currentTimeMillis();
        room.iceServers = buildIceServers();

        // 层二（ADR-003）：建房走 HSETNX，callId 已被占用则**拒绝**而不是覆盖。
        // 不拒绝会让两个不同通话被合并进同一房间 —— 那是最坏情况（互相收到对方的信令）。
        CallRoomStore.CallRoomSnapshot created = snapshotOf(room);
        if (!callRoomStore.createRoom(created)) {
            sendCallError(fromUserId, callId, CODE_CALL_ID_CONFLICT,
                    "callId 已被占用，请重新发起通话");
            return;
        }
        callRoomStore.putMember(callId, fromUserId, STATUS_ACCEPTED);
        callRoomStore.bindUser(fromUserId, callId);

        JSONObject invite = baseFrame(Constants.WS_CALL_INVITE, room);
        invite.put("fromUserId", fromUserId);
        invite.put("toUserId", toUserId);
        invite.put("groupId", groupId);
        invite.put("iceServers", room.iceServers);

        if (callType == 1) {
            sendTo(toUserId, invite);
        } else {
            for (String memberId : memberIds) {
                if (memberId.equals(fromUserId)) {
                    continue;
                }
                invite.put("toUserId", memberId);
                sendTo(memberId, invite);
            }
        }
    }

    private void handleAccept(String fromUserId, String callId) {
        CallRoom room = roomOf(callId);
        if (room == null) {
            return;
        }
        CallMember cm = room.members.get(fromUserId);
        if (cm == null) {
            cm = new CallMember(fromUserId, STATUS_ACCEPTED);
            room.members.put(fromUserId, cm);
            callRoomStore.putMember(callId, fromUserId, STATUS_ACCEPTED);
            callRoomStore.bindUser(fromUserId, callId);
        } else {
            cm.status = STATUS_ACCEPTED;
            callRoomStore.putMember(callId, fromUserId, STATUS_ACCEPTED);
        }
        room.anyAccepted = true;
        callRoomStore.setAnyAccepted(callId, true);

        // 广播 CALL_JOIN：现有成员向新成员发起 offer，新成员等待 offer（full-mesh）
        JSONObject join = baseFrame(Constants.WS_CALL_JOIN, room);
        join.put("newMemberId", fromUserId);
        join.put("members", new JSONArray(new ArrayList<>(room.members.keySet())));
        join.put("iceServers", room.iceServers);
        for (String uid : room.members.keySet()) {
            sendTo(uid, join);
        }
    }

    private void handleReject(String fromUserId, String callId, int logStatus) {
        CallRoom room = roomOf(callId);
        if (room == null) {
            return;
        }
        CallMember cm = room.members.get(fromUserId);
        int next = (logStatus == LOG_BUSY) ? STATUS_BUSY : STATUS_REJECTED;
        if (cm != null) {
            cm.status = next;
            callRoomStore.putMember(callId, fromUserId, next);
        }
        JSONObject frame = baseFrame(logStatus == LOG_BUSY ? Constants.WS_CALL_BUSY : Constants.WS_CALL_REJECT, room);
        frame.put("fromUserId", fromUserId);
        for (String uid : room.members.keySet()) {
            if (uid.equals(fromUserId)) {
                continue;
            }
            sendTo(uid, frame);
        }
        if (room.callType == 1) {
            // 单聊：唯一对方拒绝/忙线，通话结束
            endCall(room, logStatus);
        }
    }

    private void handleSignal(String fromUserId, JSONObject json) {
        String callId = json.getString("callId");
        CallRoom room = roomOf(callId);
        if (room == null) {
            return;
        }
        String toPeer = json.getString("toUserId");
        if (toPeer == null || !room.members.containsKey(fromUserId) || !room.members.containsKey(toPeer)) {
            return; // 安全检查：仅同房间成员可互发信令
        }
        String sdp = json.getString("sdp");
        if (sdp != null && sdp.length() > 20000) {
            return; // 信令帧尺寸上限
        }
        JSONObject candidate = json.getJSONObject("candidate");
        if (candidate != null && candidate.toJSONString().length() > 5000) {
            return;
        }
        JSONObject frame = new JSONObject();
        frame.put("messageType", Constants.WS_CALL_SIGNAL);
        frame.put("callId", callId);
        frame.put("fromUserId", fromUserId);
        frame.put("toUserId", toPeer);
        frame.put("signalType", json.getString("signalType"));
        frame.put("sdp", sdp);
        frame.put("candidate", candidate);
        sendTo(toPeer, frame);
    }

    private void handleHangup(String fromUserId, String callId) {
        CallRoom room = roomOf(callId);
        if (room == null) {
            return;
        }
        CallMember cm = room.members.get(fromUserId);
        if (cm != null) {
            cm.status = STATUS_LEFT;
            callRoomStore.putMember(callId, fromUserId, STATUS_LEFT);
        }
        callRoomStore.unbindUser(fromUserId);
        JSONObject frame = baseFrame(Constants.WS_CALL_HANGUP, room);
        frame.put("fromUserId", fromUserId);
        for (String uid : room.members.keySet()) {
            if (uid.equals(fromUserId)) {
                continue;
            }
            sendTo(uid, frame);
        }
        boolean callerLeft = fromUserId.equals(room.callerId);
        if (callerLeft || activeMemberCount(room) == 0) {
            endCall(room, room.anyAccepted ? LOG_ACCEPTED : LOG_MISSED);
        }
    }

    private void handleCancel(String fromUserId, String callId) {
        CallRoom room = roomOf(callId);
        if (room == null) {
            return;
        }
        if (!fromUserId.equals(room.callerId)) {
            return; // 仅发起方可以取消
        }
        JSONObject frame = baseFrame(Constants.WS_CALL_CANCEL, room);
        frame.put("fromUserId", fromUserId);
        for (String uid : room.members.keySet()) {
            if (uid.equals(fromUserId)) {
                continue;
            }
            sendTo(uid, frame);
        }
        endCall(room, LOG_CANCELED);
    }

    private void endCall(CallRoom room, int logStatus) {
        // 层二：rooms.remove(...) == null 判「已被结束」的语义，改用「先删后判」——
        // 删成功（true）说明本实例抢到了结束权，其余并发方删除返回 false 直接返回。
        boolean wonTheRace = callRoomStore.evictRoom(room.callId);
        if (!wonTheRace) {
            return; // 已被结束
        }
        CallLog callLog = new CallLog();
        callLog.setCallerId(room.callerId);
        callLog.setCallType(room.callType);
        callLog.setMediaType(room.mediaType);
        callLog.setStartTime(room.startTime);
        callLog.setEndTime(System.currentTimeMillis());
        callLog.setStatus(logStatus);
        callLog.setParticipantCount(room.members.size());
        if (room.callType == 1) {
            String peer = null;
            for (String uid : room.members.keySet()) {
                if (!uid.equals(room.callerId)) {
                    peer = uid;
                }
            }
            callLog.setPeerId(peer);
        } else {
            callLog.setGroupId(room.groupId);
        }
        callLog.setCreateTime(System.currentTimeMillis());
        try {
            callLogService.save(callLog);
        } catch (Exception e) {
            logger.error("通话记录落库失败 callId={}", room.callId, e);
        }
        for (String uid : room.members.keySet()) {
            callRoomStore.unbindUser(uid);
        }
    }

    private JSONObject baseFrame(Integer messageType, CallRoom room) {
        JSONObject f = new JSONObject();
        f.put("messageType", messageType);
        f.put("callId", room.callId);
        f.put("callType", room.callType);
        f.put("mediaType", room.mediaType);
        f.put("groupId", room.groupId);
        return f;
    }

    private void sendTo(String userId, JSONObject frame) {
        String json = frame.toJSONString();
        // 2026-10-14（call-multi-instance 层一 / ADR-001）：
        //   原实现直接 channelContextUtils.sendRawToUser(...) —— 只写**本地** ChannelGroup，
        //   而 USER_CONTEXT_MAP 是进程内的。多实例下目标用户挂在别处时**静默丢失**
        //   （查不到本地 ChannelGroup 即 return，无日志无异常）。
        //   现改为广播：各实例经本地投递各自判断，本实例的广播回声即完成本地投递。
        if (callFrameTopic != null && callFrameTopic.isBroadcastEnabled()) {
            if (callFrameTopic.broadcast(userId, json)) {
                return;   // 发布成功：本实例会收到自己的回声并本地投递
            }
            // 发布失败 → 退回本地直投，保证本实例可达的用户至少还能收到
        }
        channelContextUtils.sendRawToUser(userId, json);
    }

    private void sendCallError(String toUserId, String callId, int code, String message) {
        JSONObject frame = new JSONObject();
        frame.put("messageType", Constants.WS_CALL_REJECT);
        frame.put("callId", callId);
        frame.put("fromUserId", "system");
        frame.put("code", code);
        frame.put("message", message);
        sendTo(toUserId, frame);
    }

    /**
     * 构造随引导帧下发的 {@code iceServers}。
     *
     * <p>STUN 条目由 {@link TurnCredentialCore#stunUrls} 决定（ADR-003：留空不下发，
     * 不再回落任何公共地址）；TURN 条目的凭据形态由 {@link TurnCredentialCore} 的
     * 时效凭据 / 静态凭据判定决定（ADR-001 / ADR-002）。
     *
     * <p>⚠ 多密钥轮换：{@code static-auth-secret} 支持逗号分隔多密钥，此时按
     * <b>第一个</b> 密钥签发 —— 因为 coturn 侧新旧密钥**都**会被接受，
     * 用哪个签都有效；只有在「只剩旧密钥」或「只剩新密钥」时签发结果才会不同。
     */
    private JSONArray buildIceServers() {
        JSONArray arr = new JSONArray();
        for (String stunUrl : TurnCredentialCore.stunUrls(turnStunUrl)) {
            JSONObject stun = new JSONObject();
            stun.put("urls", stunUrl);
            arr.add(stun);
        }

        for (String url : TurnCredentialCore.turnUrls(turnUrl)) {
            JSONObject turn = new JSONObject();
            turn.put("urls", url);

            List<String> secrets = TurnCredentialCore.parseSecrets(turnStaticSecret);
            boolean ephemeral = turnTtlSeconds > 0 && !secrets.isEmpty();
            if (ephemeral) {
                // 时效凭据：username 形如 <时间戳>:<userId>，credential 为 HMAC 签名。
                // timestamp 必须在 ttl 之前发出，故用「当前时间」而非房间创建时间。
                String username = TurnCredentialCore.buildEphemeralUsername(
                        System.currentTimeMillis() / 1000L, "easychat");
                turn.put("username", username);
                turn.put("credential", TurnCredentialCore.sign(secrets.get(0), username));
            } else {
                // 静态降级（已在 logTurnConfigStatus 打过 WARN）
                if (turnUsername != null && !turnUsername.trim().isEmpty()) {
                    turn.put("username", turnUsername.trim());
                }
                if (turnCredential != null && !turnCredential.trim().isEmpty()) {
                    turn.put("credential", turnCredential.trim());
                }
            }
            arr.add(turn);
        }
        return arr;
    }

    private List<String> groupMemberIds(String groupId) {
        UserContactQuery query = new UserContactQuery();
        query.setContactId(groupId);
        query.setContactType(UserContactTypeEnum.GROUP.getType());
        List<UserContact> list = userContactService.findListByParam(query);
        List<String> ids = new ArrayList<>();
        if (list != null) {
            for (UserContact uc : list) {
                if (uc.getUserId() != null) {
                    ids.add(uc.getUserId());
                }
            }
        }
        return ids;
    }

    private int activeMemberCount(CallRoom room) {
        int n = 0;
        for (CallMember m : room.members.values()) {
            if (m.status == STATUS_INVITED || m.status == STATUS_ACCEPTED) {
                n++;
            }
        }
        return n;
    }

    // ── 层二：CallRoom（进程内视图） ↔ CallRoomSnapshot（Redis 扁平字段） ──

    /** 把房间视图拍平成可逐字段 HSET 的快照。 */
    private CallRoomStore.CallRoomSnapshot snapshotOf(CallRoom room) {
        return new CallRoomStore.CallRoomSnapshot(
                room.callId,
                room.callerId,
                room.callType,
                room.groupId,
                room.mediaType,
                room.iceServers == null ? "" : room.iceServers.toJSONString(),
                room.anyAccepted,
                room.startTime);
    }

    /**
     * 从 Redis 还原房间视图（含成员子表）。
     *
     * @return 房间不存在返回 {@code null}（调用方据此静默返回，保持既有语义）
     */
    private CallRoom roomOf(String callId) {
        CallRoomStore.CallRoomSnapshot s = callRoomStore.getRoom(callId);
        if (s == null) {
            return null;
        }
        CallRoom room = new CallRoom();
        room.callId = s.getCallId();
        room.callerId = s.getCallerId();
        room.callType = s.getCallType();
        room.groupId = s.getGroupId();
        room.mediaType = s.getMediaType();
        room.startTime = s.getStartTime();
        room.anyAccepted = s.isAnyAccepted();
        try {
            String iceJson = s.getIceServersJson();
            room.iceServers = (iceJson == null || iceJson.isEmpty())
                    ? new JSONArray()
                    // fastjson 1.x：JSON.parseArray 返回 List，不是 JSONArray；
                    // 必须用 JSONArray.parseArray 才能直接赋给 JSONArray 字段。
                    : JSONArray.parseArray(iceJson);
        } catch (Exception e) {
            logger.warn("[通话] iceServersJson 反序列化失败 callId={}", callId, e);
            room.iceServers = new JSONArray();
        }
        for (Map.Entry<String, Integer> e : callRoomStore.members(callId).entrySet()) {
            room.members.put(e.getKey(), new CallMember(e.getKey(), e.getValue()));
        }
        return room;
    }
}
