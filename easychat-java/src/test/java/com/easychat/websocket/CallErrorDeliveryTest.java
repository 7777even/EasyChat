package com.easychat.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.easychat.entity.po.UserContact;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;

/**
 * 呼叫**失败错误帧**的投递路径（遗留 #35 补修）。
 *
 * <p><b>缺陷背景（2026-10-21 实测，Redis 被阻塞时逐帧记录）</b>：
 * <pre>
 *   t=3.8s    A 发 INVITE（Redis 阻塞中）
 *   t=28.6s   A 收到第 1 份 2803        ← createRoom 的重试风暴 25.8s 才判失败
 *   t=45.3s   A 收到 4 份 2803（同时）   ← Redis 一恢复，排队的 4 条 PUBLISH 一起执行
 * </pre>
 * 即：发起方**收到 5 份**「通话服务暂时不可用」。两个成因：
 * <ol>
 *   <li><b>错误帧走了 topic</b>：目标恒为「触发本次呼叫处理的发起方」，其连接就在本实例，
 *       本可直投；走 topic 后，Redisson 的重试会排 4 条 PUBLISH，恢复后一起执行 → 4 份重复；</li>
 *   <li><b>Redisson 自动重试</b>：{@code timeout=3s} × {@code retryAttempts=3} + {@code retryInterval=1.5s}
 *       → 单个建房操作拖到 25.8s 才失败（已在 {@code RedisConfigTest} 锁住）。</li>
 * </ol>
 *
 * <p><b>本类锁定的契约</b>：
 * <ol>
 *   <li>目标**在本实例**时 → 错误帧**本地直投**，且**不得**经 topic（否则重复投递）；</li>
 *   <li>目标**不在本实例**时 → 仍须有兜底（走广播），否则错误帧会丢；</li>
 *   <li>正常呼叫（建房成功）**不得**回任何错误帧（防「恒报错」假通过）。</li>
 * </ol>
 */
public class CallErrorDeliveryTest {

    private CallService callService;
    private ChannelContextUtils channelContextUtils;
    private CallFrameTopic callFrameTopic;
    private CallRoomStore store;

    @Before
    public void setUp() {
        callService = new CallService();
        channelContextUtils = mock(ChannelContextUtils.class);
        callFrameTopic = mock(CallFrameTopic.class);
        store = mock(CallRoomStore.class);
        UserContactService userContactService = mock(UserContactService.class);
        GroupInfoService groupInfoService = mock(GroupInfoService.class);

        set(callService, "channelContextUtils", channelContextUtils);
        set(callService, "callFrameTopic", callFrameTopic);
        set(callService, "callRoomStore", store);
        set(callService, "userContactService", userContactService);
        set(callService, "groupInfoService", groupInfoService);

        // 单聊必须通过好友校验才能走到建房
        when(userContactService.getUserContactByUserIdAndContactId(anyString(), anyString()))
                .thenReturn(new UserContact());
        // 广播开关为开 —— 否则被测实现会跳过 topic，断言将失去区分力
        when(callFrameTopic.isBroadcastEnabled()).thenReturn(true);
        when(callFrameTopic.broadcast(anyString(), anyString())).thenReturn(true);
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

    /** 建房失败（callId 冲突）→ 必然走 sendCallError 路径 */
    private void triggerError() {
        when(store.createRoom(any())).thenReturn(false);
        callService.handleCallFrame("U-caller", invite("dup-call-id"));
    }

    /**
     * 🔴 核心：目标在本实例时，错误帧**只**本地直投，**不得**经 topic。
     *
     * <p>若实现仍把错误帧发到 topic：Redis 恢复后那批排队的 PUBLISH 会一起执行，
     * 发起方会收到 4~5 份重复错误帧（实测）。
     */
    @Test
    public void errorFrameIsDeliveredLocallyWhenTargetIsHere() {
        when(channelContextUtils.sendRawToUser(anyString(), anyString())).thenReturn(true);

        triggerError();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(channelContextUtils, times(1)).sendRawToUser(eq("U-caller"), cap.capture());
        verify(callFrameTopic, never()).broadcast(anyString(), anyString());

        JSONObject frame = JSON.parseObject(cap.getValue());
        assertEquals("错误码必须是 2802（callId 冲突）", 2802, frame.getIntValue("code"));
    }

    /**
     * 反向制衡：目标**不在**本实例时仍须有兜底（走广播），否则错误帧会丢。
     *
     * <p>这条防止「为了不重复而改成只本地直投」这种过度修正。
     */
    @Test
    public void errorFrameFallsBackToBroadcastWhenTargetIsElsewhere() {
        when(channelContextUtils.sendRawToUser(anyString(), anyString())).thenReturn(false);

        triggerError();

        verify(callFrameTopic, times(1)).broadcast(eq("U-caller"), anyString());
    }

    /**
     * 防「恒报错」：正常呼叫（建房成功）**不得**给发起方回错误帧，
     * 且给对端的应是 INVITE 引导帧（不含错误码）。
     *
     * <p>⚠ 注意：成功呼叫**本来就要广播** INVITE 给对端（那是跨实例投递路径），
     * 所以此处不能断言「没广播」——那会把正常功能当成缺陷。
     */
    @Test
    public void successfulCallEmitsNoErrorFrame() {
        when(store.createRoom(any())).thenReturn(true);

        callService.handleCallFrame("U-caller", invite("fresh-call-id"));

        verify(channelContextUtils, never()).sendRawToUser(anyString(), anyString());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(callFrameTopic, times(1)).broadcast(eq("U-peer"), cap.capture());
        JSONObject frame = JSON.parseObject(cap.getValue());
        assertEquals("对端收到的应是 INVITE 引导帧", -10, frame.getIntValue("messageType"));
        assertTrue("INVITE 引导帧不得带错误码", !frame.containsKey("code"));
    }
}
