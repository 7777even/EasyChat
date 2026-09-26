package com.easychat.websocket.netty;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.CallService;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;



/**
 * 设置通道共享
 */
@ChannelHandler.Sharable
@Component("handlerWebSocket")
public class HandlerWebSocket extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(HandlerWebSocket.class);

    @Resource
    private ChannelContextUtils channelContextUtils;

    @Resource
    private RedisComponet redisComponet;

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private CallService callService;

    /**
     * 当通道就绪后会调用此方法，通常我们会在这里做一些初始化操作
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Channel channel = ctx.channel();
        logger.info("有新的连接加入。。。");
    }

    /**
     * 当通道不再活跃时（连接关闭）会调用此方法，我们可以在这里做一些清理工作
     *
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("有连接已经断开。。。");
        Channel channel = ctx.channel();
        Attribute<String> attribute = channel.attr(AttributeKey.valueOf(channel.id().toString()));
        String userId = attribute.get();
        if (userId != null && callService != null) {
            // 通话中断线：视为挂断，清理对方浮窗与房间
            callService.onUserDisconnect(userId);
        }
        channelContextUtils.removeContext(ctx.channel());
    }

    /**
     * 读就绪事件 当有消息可读时会调用此方法，我们可以在这里读取消息并处理。
     *
     * @param ctx
     * @param textWebSocketFrame
     * @throws Exception
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame textWebSocketFrame) throws Exception {
        Channel channel = ctx.channel();
        Attribute<String> attribute = channel.attr(AttributeKey.valueOf(channel.id().toString()));
        String userId = attribute.get();
        if (StringTools.isEmpty(userId)) {
            return;
        }

        String text = textWebSocketFrame.text();
        // 解析 JSON 帧，按 messageType 分发
        try {
            JSONObject json = JSON.parseObject(text);
            Integer messageType = json.getInteger("messageType");
            if (messageType != null && Constants.WS_SYNC_MESSAGE_TYPE.equals(messageType)) {
                // 客户端请求补推：按 sessionId 传 lastSeq，服务端查询 seq > lastSeq 的消息推回
                handleSync(userId, json.getJSONObject("extendData"));
            } else if (messageType != null && isCallMessageType(messageType)) {
                // 语音/视频通话信令帧：交由 CallService 中继（不落库、不触发普通消息逻辑）
                callService.handleCallFrame(userId, json);
            } else {
                // 心跳或其他未知类型（含旧客户端残留的 -3 回执帧）：仅刷新心跳，静默忽略
                redisComponet.saveUserHeartBeat(userId);
            }
        } catch (Exception e) {
            // 非 JSON 文本 → 视为心跳
            redisComponet.saveUserHeartBeat(userId);
        }
    }

    /**
     * 处理客户端 SYNC 帧：按 sessionId 下发 seq > lastSeq 的消息
     * extendData 格式: { "sync": { "sessionId1": lastSeq1, "sessionId2": lastSeq2 } }
     */
    /**
     * 判断是否为语音/视频通话信令帧（负区间，与聊天消息类型 0~19 不冲突）
     */
    private boolean isCallMessageType(Integer messageType) {
        return Constants.WS_CALL_INVITE.equals(messageType)
                || Constants.WS_CALL_ACCEPT.equals(messageType)
                || Constants.WS_CALL_REJECT.equals(messageType)
                || Constants.WS_CALL_SIGNAL.equals(messageType)
                || Constants.WS_CALL_HANGUP.equals(messageType)
                || Constants.WS_CALL_CANCEL.equals(messageType)
                || Constants.WS_CALL_BUSY.equals(messageType)
                || Constants.WS_CALL_JOIN.equals(messageType);
    }

    private void handleSync(String userId, JSONObject extendData) {
        redisComponet.saveUserHeartBeat(userId);
        if (extendData == null) {
            return;
        }
        JSONObject syncObj = extendData.getJSONObject("sync");
        if (syncObj == null || syncObj.isEmpty()) {
            return;
        }
        for (String sessionId : syncObj.keySet()) {
            Long lastSeq = syncObj.getLong(sessionId);
            if (lastSeq == null) {
                continue;
            }
            ChatMessageQuery query = new ChatMessageQuery();
            query.setSessionId(sessionId);
            query.setSeqStart(lastSeq);
            query.setSeqNotNull(true);
            query.setOrderBy("seq ASC");
            java.util.List<ChatMessage> missingList = chatMessageMapper.selectList(query);
            if (missingList == null || missingList.isEmpty()) {
                continue;
            }
            logger.info("SYNC 补推: userId={}, sessionId={}, lastSeq={}, 补推{}条",
                    userId, sessionId, lastSeq, missingList.size());
            for (ChatMessage msg : missingList) {
                MessageSendDto sendDto = new MessageSendDto();
                sendDto.setMessageId(msg.getMessageId());
                sendDto.setSessionId(msg.getSessionId());
                sendDto.setMessageType(msg.getMessageType());
                sendDto.setMessageContent(msg.getMessageContent());
                sendDto.setSendUserId(msg.getSendUserId());
                sendDto.setSendUserNickName(msg.getSendUserNickName());
                sendDto.setSendTime(msg.getSendTime());
                sendDto.setContactId(msg.getContactId());
                sendDto.setContactType(msg.getContactType());
                sendDto.setSeq(msg.getSeq());
                sendDto.setStatus(msg.getStatus());
                sendDto.setFileSize(msg.getFileSize());
                sendDto.setFileName(msg.getFileName());
                sendDto.setFileType(msg.getFileType());
                sendDto.setContactName(msg.getSendUserNickName());
                channelContextUtils.sendMessage(sendDto);
            }
        }
    }

    //用于处理用户自定义的事件  当有用户事件触发时会调用此方法，例如连接超时，异常等。
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            WebSocketServerProtocolHandler.HandshakeComplete complete = (WebSocketServerProtocolHandler.HandshakeComplete) evt;
            String url = complete.requestUri();
            String token = getToken(url);
            if (token == null) {
                ctx.channel().close();
                return;
            }
            TokenUserInfoDto tokenUserInfoDto = redisComponet.getTokenUserInfoDto(token);
            if (null == tokenUserInfoDto) {
                ctx.channel().close();
                return;
            }
            /**
             * 用户加入
             */
            channelContextUtils.addContext(tokenUserInfoDto.getUserId(), ctx.channel());

        }
    }

    private String getToken(String url) {
        if (StringTools.isEmpty(url) || url.indexOf("?") == -1) {
            return null;
        }
        String[] queryParams = url.split("\\?");
        if (queryParams.length < 2) {
            return url;
        }
        String[] params = queryParams[1].split("=");
        if (params.length != 2) {
            return url;
        }
        return params[1];
    }
}