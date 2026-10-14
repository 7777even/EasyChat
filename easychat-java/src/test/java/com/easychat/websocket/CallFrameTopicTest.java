package com.easychat.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 通话信令广播的纯逻辑测试（call-multi-instance T2）。
 *
 * <p>覆盖：载荷序列化/反序列化往返、是否该广播的判定、非法入参。
 * Redisson IO 部分另见 {@code CallFrameTopicWiringTest}（集成测试用 mock 验证接线）。
 */
public class CallFrameTopicTest {

    @Test
    public void payloadRoundTrip() {
        CallFrameTopic.CallFramePayload p =
                new CallFrameTopic.CallFramePayload("U001", "{\"messageType\":-13}");

        String json = CallFrameTopic.encode(p);
        assertNotNull(json);
        CallFrameTopic.CallFramePayload back = CallFrameTopic.decode(json);

        assertEquals("targetUserId 必须往返一致", "U001", back.getTargetUserId());
        assertEquals("frameJson 必须往返一致", "{\"messageType\":-13}", back.getFrameJson());
    }

    @Test
    public void broadcastOnlyWhenTargetPresent() {
        assertTrue("有 targetUserId 才广播（避免无效流量放大）",
                CallFrameTopic.shouldBroadcast("U001"));
        assertFalse("targetUserId 为空不得广播", CallFrameTopic.shouldBroadcast(null));
        assertFalse("targetUserId 为空白不得广播", CallFrameTopic.shouldBroadcast("   "));
        assertFalse("targetUserId 为 'null' 字面量不得广播",
                CallFrameTopic.shouldBroadcast("null"));
    }

    /** 反向制衡：若 shouldBroadcast 恒真，空目标也会被广播出去（流量放大） */
    @Test
    public void shouldBroadcastNotAlwaysTrue() {
        assertFalse(CallFrameTopic.shouldBroadcast(""));
    }

    /** 反向制衡：若恒假，广播层等于关闭，跨实例投递会退回本地直投 */
    @Test
    public void shouldBroadcastNotAlwaysFalse() {
        assertTrue(CallFrameTopic.shouldBroadcast("U009"));
    }

    @Test
    public void decodeNullOrGarbageIsSafe() {
        assertEquals("反序列化 null 不应抛异常", null, CallFrameTopic.decode(null));
        assertEquals("反序列化垃圾串不应抛异常", null, CallFrameTopic.decode("{not json"));
    }

    @Test
    public void topicNameIsDistinctFromMessageTopic() {
        // 复用 message.topic 会把两种语义混在一个通道里（ADR-001）
        assertFalse("通话 Topic 必须独立于普通消息 Topic",
                "message.topic".equals(CallFrameTopic.TOPIC_NAME));
        assertNotNull(CallFrameTopic.TOPIC_NAME);
    }
}
