package com.easychat.integration;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.easychat.websocket.CallFrameTopic;
import com.easychat.websocket.ChannelContextUtils;

/**
 * 通话信令广播的**接线**验证（call-multi-instance T2）。
 *
 * <p>纯逻辑部分见 {@code CallFrameTopicTest}；本类只证明 Redisson 侧接对了：
 * <ol>
 *   <li>订阅用的是 {@link CallFrameTopic#TOPIC_NAME}，且与普通消息 Topic 不同名（ADR-001）</li>
 *   <li><b>广播出去的消息确实被本实例的监听器消费，并经 {@code sendRawToUser} 本地投递</b>
 *       —— 这是多实例跨端投递能成立的唯一依据，缺了它广播层等于没接</li>
 *   <li>{@code targetUserId} 为空时监听器不投递</li>
 * </ol>
 *
 * <p>⚠ {@code redissonClient} 已由 {@link IntegrationTestBase} mock；
 * 本类再 stub {@code getTopic(TOPIC_NAME)} 返回 mock RTopic，从而能捕获监听器。
 * {@code channelContextUtils} 必须 mock，否则 {@code sendRawToUser} 会写进空的
 * {@code USER_CONTEXT_MAP} 而无法观测。
 */
public class CallFrameTopicWiringTest extends IntegrationTestBase {

    @MockBean
    private ChannelContextUtils channelContextUtils;

    @Autowired
    private CallFrameTopic callFrameTopic;

    private RTopic topic;

    @Before
    public void stubTopicAndSubscribe() {
        topic = org.mockito.Mockito.mock(RTopic.class);
        when(redissonClient.getTopic(CallFrameTopic.TOPIC_NAME)).thenReturn(topic);
        // 启动期那次 subscribe() 拿到的 getTopic() 是 null（mock 尚未 stub），只 WARN 跳过；
        // 这里补一次真正带 stub 的订阅，才能捕获监听器做断言。
        callFrameTopic.subscribe();
    }

    /** 捕获 @PostConstruct 注册进来的监听器（若没注册，后面全部用例都无从谈起） */
    @SuppressWarnings("unchecked")
    private MessageListener<CallFrameTopic.CallFramePayload> captureListener() {
        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(CallFrameTopic.CallFramePayload.class), captor.capture());
        return (MessageListener<CallFrameTopic.CallFramePayload>) captor.getValue();
    }

    @Test
    public void subscribesToDedicatedTopicName() {
        verify(redissonClient).getTopic(CallFrameTopic.TOPIC_NAME);
        verify(topic).addListener(eq(CallFrameTopic.CallFramePayload.class), any(MessageListener.class));
        assertEquals("通话 Topic 必须独立于普通消息 Topic",
                "call.frame.topic", CallFrameTopic.TOPIC_NAME);
    }

    /**
     * 🔴 本 Change 的核心断言：广播 → 监听 → 本地投递 这条链真的通。
     *
     * <p>若监听器没注册、或 Topic 名写错、或载荷类型不匹配，
     * 多实例下 invite 会静默丢失 —— 而那种故障**没有任何异常**，只能靠本用例兜住。
     */
    @Test
    public void broadcastMessageIsConsumedAndDeliveredLocally() {
        MessageListener<CallFrameTopic.CallFramePayload> listener = captureListener();

        listener.onMessage(null, new CallFrameTopic.CallFramePayload(
                "U-target", "{\"messageType\":-13,\"callId\":\"c1\"}"));

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(channelContextUtils).sendRawToUser(userCaptor.capture(), jsonCaptor.capture());

        assertEquals("投递目标必须是广播载荷里的 targetUserId", "U-target", userCaptor.getValue());
        assertEquals("投递内容必须是原始信令帧", "{\"messageType\":-13,\"callId\":\"c1\"}", jsonCaptor.getValue());
    }

    /** 空目标不得投递（否则会向空 userId 写帧） */
    @Test
    public void blankTargetIsNotDelivered() {
        MessageListener<CallFrameTopic.CallFramePayload> listener = captureListener();

        listener.onMessage(null, new CallFrameTopic.CallFramePayload("", "{}"));

        verify(channelContextUtils, org.mockito.Mockito.never())
                .sendRawToUser(any(), any());
    }

    /** null 载荷不得让监听线程崩 */
    @Test
    public void nullPayloadIsTolerated() {
        MessageListener<CallFrameTopic.CallFramePayload> listener = captureListener();
        listener.onMessage(null, null);
        verify(channelContextUtils, org.mockito.Mockito.never()).sendRawToUser(any(), any());
    }
}
