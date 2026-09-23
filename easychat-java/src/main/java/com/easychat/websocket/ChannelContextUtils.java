package com.easychat.websocket;

import com.alibaba.fastjson.JSON;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.WsInitData;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.UserContactApplyStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.*;
import com.easychat.entity.query.*;
import com.easychat.mappers.*;
import com.easychat.redis.RedisComponet;
import com.easychat.utils.JsonUtils;
import com.easychat.utils.StringTools;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Component("channelContextUtils")
public class ChannelContextUtils {

    private static final Logger logger = LoggerFactory.getLogger(ChannelContextUtils.class);

    @Resource
    private RedisComponet redisComponet;

    public static final ConcurrentMap<String, ChannelGroup> USER_CONTEXT_MAP = new ConcurrentHashMap();

    public static final ConcurrentMap<String, ChannelGroup> GROUP_CONTEXT_MAP = new ConcurrentHashMap();

    @Resource
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private UserContactApplyMapper<UserContactApply, UserContactApplyQuery> userContactApplyMapper;

    /**
     * 加入通道
     *
     * @param userId
     * @param channel
     */
    public void addContext(String userId, Channel channel) {
        try {
            String channelId = channel.id().toString();
            AttributeKey attributeKey = null;
            if (!AttributeKey.exists(channelId)) {
                attributeKey = AttributeKey.newInstance(channel.id().toString());
            } else {
                attributeKey = AttributeKey.valueOf(channel.id().toString());
            }
            channel.attr(attributeKey).set(userId);

            List<String> contactList = redisComponet.getUserContactList(userId);
            for (String groupId : contactList) {
                if (groupId.startsWith(UserContactTypeEnum.GROUP.getPrefix())) {
                    add2Group(groupId, channel);
                }
            }
            addUserChannel(userId, channel);
            redisComponet.saveUserHeartBeat(userId);

            //更新用户最后连接时间
            UserInfo updateInfo = new UserInfo();
            updateInfo.setLastLoginTime(new Date());
            userInfoMapper.updateByUserId(updateInfo, userId);

            //给用户发送一些消息
            //获取用户最后离线时间
            UserInfo userInfo = userInfoMapper.selectByUserId(userId);
            Long sourceLastOffTime = userInfo.getLastOffTime();
            //这里避免毫秒时间差，所以减去1秒的时间
            //如果时间太久，只取最近三天的消息数
            Long lastOffTime = sourceLastOffTime;
            if (sourceLastOffTime != null && System.currentTimeMillis() - Constants.MILLISECOND_3DAYS_AGO > sourceLastOffTime) {
                lastOffTime = System.currentTimeMillis() - Constants.MILLISECOND_3DAYS_AGO;
            }

            /**
             * 1、查询会话信息 查询用户所有会话，避免换设备会话不同步
             */
            ChatSessionUserQuery sessionUserQuery = new ChatSessionUserQuery();
            sessionUserQuery.setUserId(userId);
            sessionUserQuery.setOrderBy("last_receive_time desc");
            List<ChatSessionUser> chatSessionList = chatSessionUserMapper.selectList(sessionUserQuery);
            WsInitData wsInitData = new WsInitData();
            wsInitData.setChatSessionList(chatSessionList);

            /**
             * 2、查询聊天消息
             */
            //查询用户的联系人
            UserContactQuery contactQuery = new UserContactQuery();
            contactQuery.setContactType(UserContactTypeEnum.GROUP.getType());
            contactQuery.setUserId(userId);
            List<UserContact> groupContactList = userContactMapper.selectList(contactQuery);
            List<String> groupIdList = groupContactList.stream().map(item -> item.getContactId()).collect(Collectors.toList());
            //将自己也加进去
            groupIdList.add(userId);

            ChatMessageQuery messageQuery = new ChatMessageQuery();
            messageQuery.setContactIdList(groupIdList);
            messageQuery.setLastReceiveTime(lastOffTime);
            List<ChatMessage> chatMessageList = chatMessageMapper.selectList(messageQuery);
            wsInitData.setChatMessageList(chatMessageList);

            /**
             * 3、查询好友申请
             */
            UserContactApplyQuery applyQuery = new UserContactApplyQuery();
            applyQuery.setReceiveUserId(userId);
            applyQuery.setLastApplyTimestamp(sourceLastOffTime);
            applyQuery.setStatus(UserContactApplyStatusEnum.INIT.getStatus());
            Integer applyCount = userContactApplyMapper.selectCount(applyQuery);
            wsInitData.setApplyCount(applyCount);

            //发送消息
            MessageSendDto messageSendDto = new MessageSendDto();
            messageSendDto.setMessageType(MessageTypeEnum.INIT.getType());
            messageSendDto.setContactId(userId);
            messageSendDto.setExtendData(wsInitData);

            sendMsg(messageSendDto, userId);

            //重连后补推离线缓冲队列中的实时消息（按入队正序）
            replayOfflineMessages(userId);
        } catch (Exception e) {
            logger.error("初始化链接失败", e);
        }
    }

    /**
     * 删除通道连接异常
     *
     * @param channel
     */
    public void removeContext(Channel channel) {
        Attribute<String> attribute = channel.attr(AttributeKey.valueOf(channel.id().toString()));
        String userId = attribute.get();
        if (!StringTools.isEmpty(userId)) {
            ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
            if (userGroup != null) {
                userGroup.remove(channel);
                if (userGroup.isEmpty()) {
                    USER_CONTEXT_MAP.remove(userId);
                    // 所有设备都离线了，更新断线时间
                    UserInfo userInfo = new UserInfo();
                    userInfo.setLastOffTime(System.currentTimeMillis());
                    userInfoMapper.updateByUserId(userInfo, userId);
                    redisComponet.removeUserHeartBeat(userId);
                }
            }
        }
    }

    public void closeContext(String userId) {
        if (StringTools.isEmpty(userId)) {
            return;
        }
        redisComponet.cleanUserTokenByUserId(userId);
        ChannelGroup userGroup = USER_CONTEXT_MAP.remove(userId);
        if (userGroup != null) {
            userGroup.close();
        }
    }

    public void sendMessage(MessageSendDto messageSendDto) {
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(messageSendDto.getContactId());
        switch (contactTypeEnum) {
            case USER:
                send2User(messageSendDto);
                break;
            case GROUP:
                sendMsg2Group(messageSendDto);
        }
    }

    /**
     * 发送消息给用户
     */
    private void send2User(MessageSendDto messageSendDto) {
        String contactId = messageSendDto.getContactId();
        sendMsg(messageSendDto, contactId);
        //强制下线
        if (MessageTypeEnum.FORCE_OFF_LINE.getType().equals(messageSendDto.getMessageType())) {
            closeContext(contactId);
        }
    }

    /**
     * 发送消息到组
     */
    private void sendMsg2Group(MessageSendDto messageSendDto) {
        if (messageSendDto.getContactId() == null) {
            return;
        }

        ChannelGroup group = GROUP_CONTEXT_MAP.get(messageSendDto.getContactId());
        if (group == null) {
            return;
        }
        group.writeAndFlush(new TextWebSocketFrame(JSON.toJSONString(messageSendDto)));

        //移除群聊
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getByType(messageSendDto.getMessageType());
        if (MessageTypeEnum.LEAVE_GROUP == messageTypeEnum || MessageTypeEnum.REMOVE_GROUP == messageTypeEnum) {
            String userId = (String) messageSendDto.getExtendData();
            redisComponet.removeUserContact(userId, messageSendDto.getContactId());
            ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
            if (userGroup == null) {
                return;
            }
            for (Channel ch : userGroup) {
                group.remove(ch);
            }
        }

        if (MessageTypeEnum.DISSOLUTION_GROUP == messageTypeEnum) {
            GROUP_CONTEXT_MAP.remove(messageSendDto.getContactId());
            group.close();
        }
    }


    /**
     * 向指定用户投递单条 WS 消息；若用户当前离线，压入 Redis 离线缓冲队列。
     */
    private void sendMsg(MessageSendDto messageSendDto, String reciveId) {
        if (reciveId == null) {
            return;
        }
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(reciveId);
        if (userGroup == null || userGroup.isEmpty()) {
            // 离线缓冲：把消息 JSON 压入该用户的 Redis 队列，重连后补推
            try {
                redisComponet.pushOfflineMessage(reciveId, JsonUtils.convertObj2Json(messageSendDto));
                logger.info("用户{}离线，消息已入缓冲队列, sessionId={}", reciveId, messageSendDto.getSessionId());
            } catch (Exception e) {
                logger.error("离线缓冲写入失败, userId={}", reciveId, e);
            }
            return;
        }
        //相当于客户而言，联系人就是发送人，所以这里转换一下再发送,好友打招呼信息发送给自己需要特殊处理
        if (MessageTypeEnum.ADD_FRIEND_SELF.getType().equals(messageSendDto.getMessageType())) {
            UserInfo userInfo = (UserInfo) messageSendDto.getExtendData();
            messageSendDto.setMessageType(MessageTypeEnum.ADD_FRIEND.getType());
            messageSendDto.setContactId(userInfo.getUserId());
            messageSendDto.setContactName(userInfo.getNickName());
            messageSendDto.setExtendData(null);
        } else {
            messageSendDto.setContactId(messageSendDto.getSendUserId());
            messageSendDto.setContactName(messageSendDto.getSendUserNickName());
        }
        userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(messageSendDto)));
    }

    /**
     * 发送 ACK 回执给消息发送方（用于确认服务端已接收并分配 seq）
     *
     * @param ackDto  包含 clientId / messageId / seq 的回执对象
     * @param senderId 发送方用户 ID
     */
    public void sendAck(MessageSendDto ackDto, String senderId) {
        if (senderId == null || ackDto == null) {
            return;
        }
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(senderId);
        if (userGroup == null || userGroup.isEmpty()) {
            // 发送方暂时离线，ACK 无缓冲必要——客户端重连后会通过 seq 补推拿到新消息
            logger.info("发送方{}离线，ACK 跳过", senderId);
            return;
        }
        // ACK 帧标识：messageType 使用 WS_ACK_MESSAGE_TYPE
        ackDto.setMessageType(Constants.WS_ACK_MESSAGE_TYPE);
        userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(ackDto)));
    }

    /**
     * 重连后补推离线缓冲队列中的消息（正序）
     *
     * @param userId 已重连的用户
     */
    public void replayOfflineMessages(String userId) {
        try {
            List<String> offlineList = redisComponet.popOfflineMessages(userId);
            if (offlineList == null || offlineList.isEmpty()) {
                return;
            }
            ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
            if (userGroup == null) {
                return;
            }
            logger.info("用户{}重连，补推离线消息{}条", userId, offlineList.size());
            for (String jsonMsg : offlineList) {
                userGroup.writeAndFlush(new TextWebSocketFrame(jsonMsg));
            }
        } catch (Exception e) {
            logger.error("补推离线消息失败, userId={}", userId, e);
        }
    }

    /**
     * 通知消息发送方：对方已送达 / 已读
     *
     * @param senderUserId 消息发送方 userId
     * @param ackUserId    确认方的 userId
     * @param messageId    消息 ID
     * @param contactType  联系人类型
     * @param ackType      2=已送达 3=已读
     */
    public void sendAckNotify(String senderUserId, String ackUserId, Long messageId,
                              Integer contactType, Integer ackType) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(senderUserId);
        if (userGroup == null || userGroup.isEmpty()) {
            // 发送方全部离线，无需通知
            return;
        }
        MessageSendDto notify = new MessageSendDto();
        notify.setMessageType(Constants.WS_ACK_NOTIFY_MESSAGE_TYPE);
        notify.setMessageId(messageId);
        notify.setContactId(ackUserId);      // 对方 ID
        notify.setContactName(ackUserId);
        notify.setContactType(contactType);
        notify.setSeq(0L);
        // extendData 携带 ackType
        notify.setExtendData(ackType);
        userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(notify)));
        logger.info("ackNotify -> sender={}, ackUser={}, msgId={}, ackType={}",
                senderUserId, ackUserId, messageId, ackType);
    }

    private void add2Group(String groupId, Channel context) {
        ChannelGroup group = GROUP_CONTEXT_MAP.get(groupId);
        if (group == null) {
            group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
            GROUP_CONTEXT_MAP.put(groupId, group);
        }
        if (context == null) {
            return;
        }
        group.add(context);
    }

    public void addUser2Group(String userId, String groupId) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
        if (userGroup != null) {
            for (Channel ch : userGroup) {
                add2Group(groupId, ch);
            }
        }
    }

    /**
     * 为用户添加一个新的 WS 连接（多端并发安全）。
     * 如果该用户尚无 ChannelGroup，会创建一个；否则加入已有 Group。
     */
    public void addUserChannel(String userId, Channel channel) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.computeIfAbsent(userId,
                k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
        userGroup.add(channel);
    }

    /**
     * 广播 SYNC_SESSION 帧给指定用户的所有在线设备（多端会话同步）。
     * 用于发送消息后，让发送方的其他设备刷新会话列表（最后消息 / 未读数）。
     *
     * @param userId     发送方用户 ID（其所有在线设备都会收到）
     * @param extendData 会话同步数据（sessionId / lastMessage / lastReceiveTime 等）
     */
    public void broadcastSyncSession(String userId, Object extendData) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
        if (userGroup == null || userGroup.isEmpty()) {
            return;
        }
        MessageSendDto syncSession = new MessageSendDto();
        syncSession.setMessageType(Constants.WS_SYNC_SESSION_MESSAGE_TYPE);
        syncSession.setExtendData(extendData);
        userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(syncSession)));
        logger.debug("SYNC_SESSION broadcast -> userId={}, devices={}", userId, userGroup.size());
    }

    /**
     * 获取指定 userId 的所有活跃 Channel 数量（用于日志 / 调试）。
     */
    public int getUserChannelCount(String userId) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
        return userGroup == null ? 0 : userGroup.size();
    }
}
