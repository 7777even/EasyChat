package com.easychat.websocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;

/**
 * callId 冲突必须被**拒绝**（call-multi-instance T7 / ADR-003）。
 *
 * <p>背景：客户端用 {@code crypto.randomUUID()} 生成 callId，碰撞概率可忽略，
 * 因此我们<b>不改协议</b>。但「静默合并」的后果远大于改协议的代价 ——
 * 两个不同通话会被合并进同一房间，互相收到对方的信令，且没有任何报错。
 * 故建房走 HSETNX，失败即回 {@code CODE_CALL_ID_CONFLICT} 并**不建房**。
 */
public class CallIdConflictTest {

    private CallService callService;
    private CallRoomStore store;
    private ChannelContextUtils channelContextUtils;

    @Before
    public void setUp() {
        callService = new CallService();
        channelContextUtils = mock(ChannelContextUtils.class);
        store = mock(CallRoomStore.class);
        UserContactService userContactService = mock(UserContactService.class);
        GroupInfoService groupInfoService = mock(GroupInfoService.class);

        set(callService, "channelContextUtils", channelContextUtils);
        set(callService, "callRoomStore", store);
        set(callService, "userContactService", userContactService);
        set(callService, "groupInfoService", groupInfoService);
        // 单聊必须通过好友校验才能走到建房，这里给一个「是好友」
        when(userContactService.getUserContactByUserIdAndContactId(anyString(), anyString()))
                .thenReturn(new com.easychat.entity.po.UserContact());
    }

    private static void set(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = CallService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 " + field + " 失败", e);
        }
    }

    private JSONObject invite(String callId) {
        JSONObject json = new JSONObject();
        json.put("messageType", -10);   // Constants.WS_CALL_INVITE
        json.put("callId", callId);
        json.put("callType", 1);
        json.put("mediaType", 1);
        json.put("toUserId", "U-peer");
        return json;
    }

    /** 抓 sendRawToUser 的最后一帧，供断言 code/message */
    private JSONObject lastFrame() {
        org.mockito.ArgumentCaptor<String> cap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(channelContextUtils, org.mockito.Mockito.atLeastOnce())
                .sendRawToUser(anyString(), cap.capture());
        return JSON.parseObject(cap.getValue());
    }

    /**
     * 🔴 核心：callId 已占用时必须拒绝，且**不得建房、不得绑定用户**。
     *
     * <p>只断言「回了错误」还不够 —— 若实现仍把房间写进去，
     * 两个不同通话就已被合并（错误帧只是让用户看到，房间已然污染）。
     */
    @Test
    public void conflictIsRejectedAndRoomLeftUntouched() {
        // HSETNX 失败：callId 已被另一个（可能跨实例的）通话占用
        when(store.createRoom(any())).thenReturn(false);

        callService.handleCallFrame("U-caller", invite("dup-call-id"));

        JSONObject frame = lastFrame();
        assertTrue("必须回 reject 帧", frame.getIntValue("messageType") == -12
                || frame.getIntValue("messageType") == -11
                || frame.containsKey("code"));
        assertTrue("错误码必须是 2802（callId 冲突）",
                frame.getIntValue("code") == 2802);

        // 关键：拒绝之后不得再往房间里加人/绑用户
        verify(store, org.mockito.Mockito.never()).putMember(anyString(), anyString(), any(Integer.class));
        verify(store, org.mockito.Mockito.never()).bindUser(anyString(), anyString());
    }

    /** 未冲突时应正常建房并绑定发起方（防「恒拒绝」假通过） */
    @Test
    public void freeCallIdCreatesRoomNormally() {
        when(store.createRoom(any())).thenReturn(true);

        callService.handleCallFrame("U-caller", invite("fresh-call-id"));

        verify(store, org.mockito.Mockito.times(1)).createRoom(any());
        verify(store, org.mockito.Mockito.times(1)).putMember(eq("fresh-call-id"), eq("U-caller"), any(Integer.class));
        verify(store, org.mockito.Mockito.times(1)).bindUser(eq("U-caller"), eq("fresh-call-id"));
        assertFalse("建房成功不得回错误帧", lastFrame().containsKey("code"));
    }
}
