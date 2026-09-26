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

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
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
    @Value("${easychat.call.max-participants:6}")
    private int maxParticipants;

    /** callId -> 通话房间（仅用于信令路由与 call_log 落库） */
    private final ConcurrentMap<String, CallRoom> rooms = new ConcurrentHashMap<>();
    /** userId -> callId（同一用户同一时刻仅在一个通话中） */
    private final ConcurrentMap<String, String> userCall = new ConcurrentHashMap<>();

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
     * 连接断开：视为挂断，清理对方浮窗与房间
     */
    public void onUserDisconnect(String userId) {
        String callId = userCall.get(userId);
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
        room.members.put(fromUserId, new CallMember(fromUserId, STATUS_ACCEPTED));
        rooms.put(callId, room);
        userCall.put(fromUserId, callId);

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
        CallRoom room = rooms.get(callId);
        if (room == null) {
            return;
        }
        CallMember cm = room.members.get(fromUserId);
        if (cm == null) {
            cm = new CallMember(fromUserId, STATUS_ACCEPTED);
            room.members.put(fromUserId, cm);
            userCall.put(fromUserId, callId);
        } else {
            cm.status = STATUS_ACCEPTED;
        }
        room.anyAccepted = true;

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
        CallRoom room = rooms.get(callId);
        if (room == null) {
            return;
        }
        CallMember cm = room.members.get(fromUserId);
        if (cm != null) {
            cm.status = (logStatus == LOG_BUSY) ? STATUS_BUSY : STATUS_REJECTED;
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
        CallRoom room = rooms.get(callId);
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
        CallRoom room = rooms.get(callId);
        if (room == null) {
            return;
        }
        CallMember cm = room.members.get(fromUserId);
        if (cm != null) {
            cm.status = STATUS_LEFT;
        }
        userCall.remove(fromUserId);
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
        CallRoom room = rooms.get(callId);
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
        if (rooms.remove(room.callId) == null) {
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
            userCall.remove(uid);
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
        channelContextUtils.sendRawToUser(userId, frame.toJSONString());
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

    private JSONArray buildIceServers() {
        JSONArray arr = new JSONArray();
        JSONObject stun = new JSONObject();
        stun.put("urls", "stun:stun.l.google.com:19302");
        arr.add(stun);
        if (turnUrl != null && !turnUrl.trim().isEmpty()) {
            JSONObject turn = new JSONObject();
            turn.put("urls", turnUrl.trim());
            if (turnUsername != null && !turnUsername.trim().isEmpty()) {
                turn.put("username", turnUsername.trim());
            }
            if (turnCredential != null && !turnCredential.trim().isEmpty()) {
                turn.put("credential", turnCredential.trim());
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
}
