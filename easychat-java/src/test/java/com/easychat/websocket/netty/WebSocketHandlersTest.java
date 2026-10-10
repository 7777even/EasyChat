package com.easychat.websocket.netty;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.websocket.CallService;
import com.easychat.websocket.ChannelContextUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Netty WebSocket 链路的**行为**测试（{@link HandlerWebSocket} + {@link HandlerHeartBeat}）。
 *
 * <p>用 {@link EmbeddedChannel} 走真实管线（真实编解码/事件分发），而非直接调方法——
 * 处理器链的顺序、{@code SimpleChannelInboundHandler} 的自动释放、{@code ChannelDuplexHandler}
 * 的读写事件都只有走管线才验得到。
 *
 * <p>被测点（均为「断了不报错、只是消息静默不达」的形态）：
 * <ol>
 *   <li>握手鉴权：无 token / token 无效 → <b>关闭通道且不加入上下文</b>；有效 → addContext</li>
 *   <li>token 解析：无 {@code ?} → null；{@code ?token=abc} → abc；畸形 → 整串 url</li>
 *   <li>未绑定 userId 的通道收到帧 → 直接忽略（不解析、不刷心跳）</li>
 *   <li>帧分发四分支：SYNC(-2) / 通话(-10~-17) / 正在输入(21) / 状态变更(23)，
 *       其余（含非 JSON 文本）一律刷心跳——这是旧客户端回执帧的兼容点</li>
 *   <li>正在输入帧缺 contactId → 不中继</li>
 *   <li>状态变更帧缺 status → 不写 Redis、不广播</li>
 *   <li>连接断开：已绑定 userId → 通知通话服务挂断；随后必须移除上下文</li>
 *   <li>心跳：读空闲 → 关闭连接；写空闲 → 下发 "heart"；非空闲事件 → 不动</li>
 * </ol>
 */
class WebSocketHandlersTest {

    private static final String USER_ID = "U001";
    private static final String TOKEN = "tok-abc";

    @Mock
    private ChannelContextUtils channelContextUtils;
    @Mock
    private RedisComponet redisComponet;
    @Mock
    private ChatMessageMapper<com.easychat.entity.po.ChatMessage, com.easychat.entity.query.ChatMessageQuery> chatMessageMapper;
    @Mock
    private CallService callService;

    private HandlerWebSocket handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new HandlerWebSocket();
        inject(handler, "channelContextUtils", channelContextUtils);
        inject(handler, "redisComponet", redisComponet);
        inject(handler, "chatMessageMapper", chatMessageMapper);
        inject(handler, "callService", callService);
    }

    private static void inject(Object target, String field, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 走真实管线的通道，并按生产代码同样的键规则绑定 userId */
    private EmbeddedChannel bindUser(String userId) {
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        if (userId != null) {
            Channel c = ch.pipeline().channel();
            c.attr(AttributeKey.<String>valueOf(c.id().toString())).set(userId);
        }
        return ch;
    }

    private static void handshake(EmbeddedChannel ch, String uri) {
        // Netty 4.1.50 的 HandshakeComplete 构造器是 **private**，
        // 且签名已不收 FullHttpRequest（早期版本才有），故反射构造。
        // ⚠ 升级 Netty 时此处会失效——那是好事：它会逼着人重新确认握手事件的构造方式。
        try {
            java.lang.reflect.Constructor<WebSocketServerProtocolHandler.HandshakeComplete> ctor =
                    WebSocketServerProtocolHandler.HandshakeComplete.class
                            .getDeclaredConstructor(String.class, HttpHeaders.class, String.class);
            ctor.setAccessible(true);
            ch.pipeline().fireUserEventTriggered(ctor.newInstance(uri, new DefaultHttpHeaders(), null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造握手完成事件（Netty 版本可能已变）", e);
        }
    }

    // ==================== 握手鉴权 ====================

    @Test
    @DisplayName("握手：URL 无 token → 关闭通道，且不加入上下文")
    void handshakeWithoutTokenClosesChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        handshake(ch, "/ws");

        assertFalse(ch.isOpen(), "无 token 必须关闭连接");
        verify(channelContextUtils, never()).addContext(anyString(), any(Channel.class));
    }

    @Test
    @DisplayName("握手：token 无效（Redis 查不到会话）→ 关闭通道")
    void handshakeWithInvalidTokenClosesChannel() {
        when(redisComponet.getTokenUserInfoDto(TOKEN)).thenReturn(null);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        handshake(ch, "/ws?token=" + TOKEN);

        assertFalse(ch.isOpen());
        verify(channelContextUtils, never()).addContext(anyString(), any(Channel.class));
    }

    @Test
    @DisplayName("握手：token 有效 → 以会话里的 userId 加入上下文（不用 URL 里的任何身份）")
    void handshakeWithValidTokenAddsContext() {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(USER_ID);
        when(redisComponet.getTokenUserInfoDto(TOKEN)).thenReturn(dto);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        handshake(ch, "/ws?token=" + TOKEN);

        verify(channelContextUtils).addContext(eq(USER_ID), any(Channel.class));
    }

    @Test
    @DisplayName("token 解析：无 ? → null；正常 → token；畸形参数 → 整串 url（不得当作 token 用）")
    void tokenParsing() {
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        ChannelHandlerContext ctx = ch.pipeline().firstContext();

        // 无 ? ：无 token 的握手会被关闭 → 不应加入上下文
        handshake(ch, "/ws");
        assertFalse(ch.isOpen());

        // token 无效时按原样传入 Redis（拼出的键不含 token 前缀以外的字符）
        when(redisComponet.getTokenUserInfoDto(null)).thenReturn(null);
        handshake(ch, "/ws?a=b&c=d");
        verify(redisComponet, never()).getTokenUserInfoDto(eq("/ws?a=b&c=d"));
        assertNotNull(ctx);
    }

    // ==================== 帧分发 ====================

    @Test
    @DisplayName("未绑定 userId 的通道收到帧 → 直接忽略（不解析、不刷心跳）")
    void frameFromUnboundChannelIsIgnored() {
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(new TextWebSocketFrame("{\"messageType\":21,\"contactId\":\"U010\",\"typing\":true}"));

        verify(redisComponet, never()).saveUserHeartBeat(anyString());
        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
    }

    @Test
    @DisplayName("非 JSON 文本（心跳）→ 只刷心跳，不报错")
    void plainTextFrameRefreshesHeartbeat() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame("heart"));

        verify(redisComponet).saveUserHeartBeat(USER_ID);
        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
    }

    @Test
    @DisplayName("未知类型帧 → 刷心跳（旧客户端回执帧兼容点）")
    void unknownFrameTypeRefreshesHeartbeat() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame("{\"messageType\":-3}"));

        verify(redisComponet).saveUserHeartBeat(USER_ID);
    }

    @Test
    @DisplayName("正在输入帧(21)：中继给对方，且接收方视角的 contactId 是发送者本人")
    void typingStatusIsRelayed() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame(
                "{\"messageType\":21,\"contactId\":\"U999\",\"typing\":true,\"sessionId\":\"S1\"}"));

        ArgumentCaptor<MessageSendDto> captor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(captor.capture());
        MessageSendDto dto = captor.getValue();
        assertEquals(MessageTypeEnum.TYPING_STATUS.getType(), dto.getMessageType());
        // 关键：contactId 必须是「发送者」，否则对方客户端会找不到会话
        assertEquals(USER_ID, dto.getContactId());
        assertEquals(USER_ID, dto.getSendUserId());
        assertEquals("S1", dto.getSessionId());
        // getExtendData() 声明为 Object，需转型后才能取值
        assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) dto.getExtendData()).get("typing"));
    }

    @Test
    @DisplayName("正在输入帧缺 contactId → 不中继（否则会广播给未知对象）")
    void typingStatusWithoutContactIdIsDropped() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame("{\"messageType\":21,\"typing\":true}"));

        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
    }

    @Test
    @DisplayName("用户状态变更帧(23)：写 Redis + 广播给好友")
    void userStatusChangeUpdatesAndBroadcasts() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame("{\"messageType\":23,\"status\":2}"));

        verify(redisComponet).updateUserStatus(USER_ID, 2);
        verify(channelContextUtils).broadcastOnlineStatus(USER_ID, 2);
    }

    @Test
    @DisplayName("用户状态变更帧缺 status → 既不写 Redis 也不广播（不得把状态清空）")
    void userStatusChangeWithoutStatusIsDropped() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame("{\"messageType\":23}"));

        verify(redisComponet, never()).updateUserStatus(anyString(), anyInt());
        verify(channelContextUtils, never()).broadcastOnlineStatus(anyString(), anyInt());
    }

    @Test
    @DisplayName("通话信令帧(-10~-17) → 交 CallService 中继，不落普通消息逻辑")
    void callFramesGoToCallService() {
        EmbeddedChannel ch = bindUser(USER_ID);

        for (int type = Constants.WS_CALL_INVITE; type >= Constants.WS_CALL_JOIN; type--) {
            ch.writeInbound(new TextWebSocketFrame("{\"messageType\":" + type + "}"));
        }

        // 8 类通话帧（-10~-17）
        verify(callService, org.mockito.Mockito.times(8)).handleCallFrame(eq(USER_ID), any());
        // 不得被当成普通消息中继
        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
        verify(redisComponet, never()).saveUserHeartBeat(anyString());
    }

    // ==================== SYNC 补推 ====================

    @Test
    @DisplayName("SYNC 帧：无 extendData → 只刷心跳、不查库")
    void syncWithoutExtendDataOnlyRefreshesHeartbeat() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame(
                "{\"messageType\":" + Constants.WS_SYNC_MESSAGE_TYPE + "}"));

        verify(redisComponet).saveUserHeartBeat(USER_ID);
        verify(chatMessageMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("SYNC 帧：按 sessionId + seq>lastSeq 补推，且补推内容逐字段映射")
    void syncPushesMissingMessages() {
        EmbeddedChannel ch = bindUser(USER_ID);
        com.easychat.entity.po.ChatMessage msg = new com.easychat.entity.po.ChatMessage();
        msg.setMessageId(9001L);
        msg.setSessionId("S1");
        msg.setMessageType(2);
        msg.setMessageContent("补推的内容");
        msg.setSendUserId("U010");
        msg.setSeq(123L);
        msg.setFileName("a.png");
        msg.setFileType(0);
        msg.setFileSize(10L);
        when(chatMessageMapper.selectList(any())).thenReturn(Collections.singletonList(msg));

        ch.writeInbound(new TextWebSocketFrame(
                "{\"messageType\":" + Constants.WS_SYNC_MESSAGE_TYPE
                        + ",\"extendData\":{\"sync\":{\"S1\":100}}}"));

        ArgumentCaptor<com.easychat.entity.query.ChatMessageQuery> q =
                ArgumentCaptor.forClass(com.easychat.entity.query.ChatMessageQuery.class);
        verify(chatMessageMapper).selectList(q.capture());
        assertEquals("S1", q.getValue().getSessionId());
        assertEquals(Long.valueOf(100L), q.getValue().getSeqStart());
        assertEquals(Boolean.TRUE, q.getValue().getSeqNotNull());

        ArgumentCaptor<MessageSendDto> dto = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dto.capture());
        assertEquals(Long.valueOf(9001L), dto.getValue().getMessageId());
        assertEquals("补推的内容", dto.getValue().getMessageContent());
        assertEquals(Long.valueOf(123L), dto.getValue().getSeq());
    }

    @Test
    @DisplayName("SYNC 帧：lastSeq 为 null 的会话跳过，不构造全量查询")
    void syncSkipsNullLastSeq() {
        EmbeddedChannel ch = bindUser(USER_ID);

        ch.writeInbound(new TextWebSocketFrame(
                "{\"messageType\":" + Constants.WS_SYNC_MESSAGE_TYPE
                        + ",\"extendData\":{\"sync\":{\"S1\":null}}}"));

        verify(chatMessageMapper, never()).selectList(any());
        verify(redisComponet).saveUserHeartBeat(USER_ID);
    }

    // ==================== 连接断开 ====================

    @Test
    @DisplayName("连接断开：已绑定 userId → 通知通话服务挂断，并移除上下文")
    void disconnectNotifiesCallServiceAndRemovesContext() {
        EmbeddedChannel ch = bindUser(USER_ID);
        Channel c = ch.pipeline().channel();

        c.close();

        verify(callService).onUserDisconnect(USER_ID);
        verify(channelContextUtils).removeContext(c);
    }

    @Test
    @DisplayName("连接断开：未绑定 userId → 不通知通话服务，但仍移除上下文（防上下文泄漏）")
    void disconnectWithoutUserStillRemovesContext() {
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        Channel c = ch.pipeline().channel();

        c.close();

        verify(callService, never()).onUserDisconnect(anyString());
        verify(channelContextUtils).removeContext(c);
    }

    // ==================== 心跳 ====================

    @Test
    @DisplayName("读空闲 → 关闭连接（客户端长时间不发心跳即回收）")
    void readerIdleClosesChannel() {
        HandlerHeartBeat hb = new HandlerHeartBeat();
        EmbeddedChannel ch = new EmbeddedChannel(hb);
        ch.pipeline().channel().attr(AttributeKey.<String>valueOf(
                ch.pipeline().channel().id().toString())).set(USER_ID);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);

        assertFalse(ch.isOpen());
    }

    @Test
    @DisplayName("写空闲 → 下发 \"heart\" 保活帧")
    void writerIdleSendsHeartFrame() {
        HandlerHeartBeat hb = new HandlerHeartBeat();
        EmbeddedChannel ch = new EmbeddedChannel(hb);

        ch.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT);

        Object out = ch.readOutbound();
        assertNotNull(out, "写空闲必须下发保活帧");
        assertEquals("heart", out.toString());
        assertTrue(ch.isOpen());
    }

    @Test
    @DisplayName("非空闲事件（如握手完成）→ 心跳处理器不干预")
    void nonIdleEventIsIgnored() {
        HandlerHeartBeat hb = new HandlerHeartBeat();
        EmbeddedChannel ch = new EmbeddedChannel(hb);

        ch.pipeline().fireUserEventTriggered("some-other-event");

        assertTrue(ch.isOpen());
        org.junit.jupiter.api.Assertions.assertNull(ch.readOutbound());
    }
}
