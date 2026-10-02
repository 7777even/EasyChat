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
import java.util.Set;
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

            // 更新用户在线状态为在线，并向所有好友广播
            redisComponet.updateUserStatus(userId, com.easychat.entity.enums.OnlineStatusEnum.ONLINE.getStatus());
            broadcastOnlineStatus(userId, com.easychat.entity.enums.OnlineStatusEnum.ONLINE.getStatus());

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
             * 3、查询好友申请（与 /contact/loadApply 同一可见性口径，见 query_condition 的 currentUserId 分支）
             */
            UserContactApplyQuery applyQuery = new UserContactApplyQuery();
            applyQuery.setCurrentUserId(userId);
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
                    // 更新用户在线状态为离线，并向所有好友广播
                    redisComponet.updateUserStatus(userId, com.easychat.entity.enums.OnlineStatusEnum.OFFLINE.getStatus());
                    broadcastOnlineStatus(userId, com.easychat.entity.enums.OnlineStatusEnum.OFFLINE.getStatus());
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

    /**
     * 直推原始 WS 帧给指定用户（不重复序列化、不做联系人转换、不进离线缓冲）。
     * 用于实时性要求高的信令帧（如语音/视频通话），目标离线则静默丢弃。
     */
    public void sendRawToUser(String userId, String json) {
        if (StringTools.isEmpty(userId) || json == null) {
            return;
        }
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
        if (userGroup == null || userGroup.isEmpty()) {
            return;
        }
        userGroup.writeAndFlush(new TextWebSocketFrame(json));
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
        // 撤回/管理端删除帧多端同步（C4 + ADR-003）：必须在 sendMsg→applyContactConvert 改写
        // contactId 之前取未转换副本投递给发送方自己的设备，保证副本 contactId 保持
        // 「会话对方」语义，否则发送方会以 contactId=自己 新建脏会话（见 design ADR-001）。
        if (MessageTypeEnum.RECALL_MESSAGE.getType().equals(messageSendDto.getMessageType())
                || MessageTypeEnum.ADMIN_DELETE.getType().equals(messageSendDto.getMessageType())) {
            sendRecallToSenderDevices(messageSendDto);
        }
        sendMsg(messageSendDto, contactId);
        //强制下线
        if (MessageTypeEnum.FORCE_OFF_LINE.getType().equals(messageSendDto.getMessageType())) {
            closeContext(contactId);
        }
    }

    /**
     * 撤回/管理端删除帧发送方副本投递：把未经联系人转换的帧直投 sendUserId 自己的全部在线设备，
     * 使发送方的其他设备实时看到撤回/删除（spec: 跨端消息撤回同步 + ADR-003 删除同步）。
     * <p>
     * 仅单聊（USER 分支）需要：群聊中发送者本就是群成员，经 sendMsg2Group 已天然收到。
     * 发送方无在线设备时按帧类型分流（ADR-003 本期增强）：
     * <ul>
     *   <li>20 管理端删除 → 入发送方离线缓冲，重连时 replayOfflineMessages 由
     *       「规则①：sendUserId=收件人」跳过转换补推（DB 过滤后历史拉取救不了本地已有行）；</li>
     *   <li>14 撤回 → 维持现状跳过（由 DB 已改写的撤回内容在拉取历史时兜底）。</li>
     * </ul>
     */
    private void sendRecallToSenderDevices(MessageSendDto messageSendDto) {
        String sendUserId = messageSendDto.getSendUserId();
        // contactId 尚未转换；若与 sendUserId 相同说明本就是投给自己的脏数据帧，不再重复投递
        if (sendUserId == null || sendUserId.equals(messageSendDto.getContactId())) {
            return;
        }
        ChannelGroup senderGroup = USER_CONTEXT_MAP.get(sendUserId);
        if (senderGroup == null || senderGroup.isEmpty()) {
            if (MessageTypeEnum.ADMIN_DELETE.getType().equals(messageSendDto.getMessageType())) {
                // ADR-003 增强：20 帧发送方副本入其离线缓冲（未转换副本），重连补推
                redisComponet.pushOfflineMessage(sendUserId, JsonUtils.convertObj2Json(messageSendDto));
                logger.info("删除帧发送方副本入离线缓冲 sendUserId={}", sendUserId);
            } else {
                logger.debug("撤回帧发送方副本跳过：sendUserId={} 无在线设备", sendUserId);
            }
            return;
        }
        senderGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(messageSendDto)));
        logger.info("撤回/删除帧发送方副本已投递 sendUserId={}, devices={}", sendUserId, senderGroup.size());
    }

    /**
     * 用户当前是否有在线 WS 通道（用于管理端删除帧的离线成员补推分流）
     */
    public boolean isUserOnline(String userId) {
        if (userId == null) {
            return false;
        }
        ChannelGroup group = USER_CONTEXT_MAP.get(userId);
        return group != null && !group.isEmpty();
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
     * 需要做「联系人=发送人」语义转换的聊天会话消息帧类型白名单。
     * <p>
     * 在接收者视角，其会话联系人就是消息发送人，因此发送给接收者的消息帧
     * 必须把 contactId/contactName 置为发送方；而 INIT(0)/FORCE_OFF_LINE(7)/
     * 朋友圈(15~18) 等帧的 contactId 语义不同，不得转换。
     */
    private static final Set<Integer> CONTACT_CONVERT_TYPES = Set.of(
            MessageTypeEnum.ADD_FRIEND.getType(),           // 1 添加好友打招呼
            MessageTypeEnum.CHAT.getType(),                 // 2 普通聊天
            MessageTypeEnum.CONTACT_APPLY.getType(),        // 4 好友申请
            MessageTypeEnum.MEDIA_CHAT.getType(),           // 5 媒体文件
            MessageTypeEnum.FILE_UPLOAD.getType(),          // 6 文件上传完成
            MessageTypeEnum.ADD_GROUP.getType(),            // 9 加入群聊
            MessageTypeEnum.CONTACT_NAME_UPDATE.getType(),  // 10 更新群昵称
            MessageTypeEnum.LEAVE_GROUP.getType(),          // 11 退出群聊
            MessageTypeEnum.REMOVE_GROUP.getType(),         // 12 被移出群聊
            MessageTypeEnum.RECALL_MESSAGE.getType(),       // 14 撤回消息
            MessageTypeEnum.ADMIN_DELETE.getType(),         // 20 管理端删除消息（ADR-003）
            MessageTypeEnum.NUDGE.getType()                 // 26 拍一拍
    );

    /**
     * 向指定用户投递单条 WS 消息；若用户当前离线，压入 Redis 离线缓冲队列。
     */
    private void sendMsg(MessageSendDto messageSendDto, String reciveId) {
        if (reciveId == null) {
            return;
        }
        // 联系人语义转换必须先于「在线/离线」判断完成：保证离线缓冲队列中
        // 存储的 JSON 与在线直推语义一致，否则重连补推时前端会以 contactId=自己
        // 新建脏会话（表现为会话列表出现重复条目 + 头像错用当前用户头像）。
        applyContactConvert(messageSendDto);
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
        userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(messageSendDto)));
    }

    /**
     * 联系人语义转换：聊天会话消息帧在接收者视角，联系人 = 发送人。
     * <p>
     * 好友打招呼（ADD_FRIEND_SELF）特殊处理：转为 ADD_FRIEND，联系人为打招呼对象本人。
     * 控制帧（INIT、强制下线、朋友圈等）不做转换，保持各自 contactId 语义。
     */
    private void applyContactConvert(MessageSendDto messageSendDto) {
        if (messageSendDto == null || messageSendDto.getMessageType() == null) {
            return;
        }
        Integer messageType = messageSendDto.getMessageType();
        // 好友打招呼信息发送给自己需要特殊处理
        if (MessageTypeEnum.ADD_FRIEND_SELF.getType().equals(messageType)) {
            UserInfo userInfo = (UserInfo) messageSendDto.getExtendData();
            if (userInfo != null) {
                messageSendDto.setMessageType(MessageTypeEnum.ADD_FRIEND.getType());
                messageSendDto.setContactId(userInfo.getUserId());
                messageSendDto.setContactName(userInfo.getNickName());
                messageSendDto.setExtendData(null);
            }
            return;
        }
        // 仅聊天会话帧做联系人转换
        if (!CONTACT_CONVERT_TYPES.contains(messageType)) {
            return;
        }
        // 规则②（ADR-003）：contactType=1 的群聊帧 contactId 是群ID、不是「发送人」语义 → 跳过转换，
        // 否则客户端会凭空生成与发送人的单聊脏会话（离线补推路径 sendMsg2Group 不经此处，天然无此问题）
        if (Integer.valueOf(1).equals(messageSendDto.getContactType())) {
            return;
        }
        // 相当于客户而言，联系人就是发送人，所以转换后再发送
        messageSendDto.setContactId(messageSendDto.getSendUserId());
        messageSendDto.setContactName(messageSendDto.getSendUserNickName());
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
     * <p>
     * 补推前对每条消息执行联系人语义转换（幂等：已转换过的帧再次转换结果不变），
     * 以兼容历史脏数据（修复前入队的队列 JSON 中 contactId=接收者自己）。
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
                // 反序列化为对象后转换，确保历史脏数据也能纠正为发送方联系人
                try {
                    MessageSendDto sendDto = JsonUtils.convertJson2Obj(jsonMsg, MessageSendDto.class);
                    // 规则①（ADR-003）：sendUserId=收件人本人 → 该帧是发给发送方自己的副本，
                    // contactId 本就是「会话对方」语义；再转换会把 contactId 覆写为收件人自己 → 脏会话，跳过。
                    // 正常来向帧 sendUserId≠收件人，仍走转换；群聊帧由下方规则②（contactType=1）拦截。
                    if (sendDto != null && !userId.equals(sendDto.getSendUserId())) {
                        applyContactConvert(sendDto);
                    }
                    userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(sendDto)));
                } catch (Exception parseError) {
                    logger.warn("补推消息解析失败，原样透传: {}", jsonMsg, parseError);
                    userGroup.writeAndFlush(new TextWebSocketFrame(jsonMsg));
                }
            }
        } catch (Exception e) {
            logger.error("补推离线消息失败, userId={}", userId, e);
        }
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
     * 广播会话用户级属性变更帧（置顶 / 免打扰 / 草稿）给指定用户的所有在线设备。
     *
     * @param userId     用户 ID
     * @param action     变更动作：top / noDisturb / draft
     * @param sessionId  会话 ID
     * @param contactId  联系人 ID
     * @param value      新值
     */
    public void broadcastSessionUserSync(String userId, String action, String sessionId, String contactId, Object value) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
        if (userGroup == null || userGroup.isEmpty()) {
            return;
        }
        MessageSendDto syncDto = new MessageSendDto();
        syncDto.setMessageType(Constants.WS_SYNC_SESSION_USER_MESSAGE_TYPE);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("action", action);
        data.put("sessionId", sessionId);
        data.put("contactId", contactId);
        data.put("value", value);
        syncDto.setExtendData(data);
        userGroup.writeAndFlush(new TextWebSocketFrame(JsonUtils.convertObj2Json(syncDto)));
        logger.debug("SYNC_SESSION_USER broadcast -> userId={}, action={}, devices={}", userId, action, userGroup.size());
    }

    /**
     * 获取指定 userId 的所有活跃 Channel 数量（用于日志 / 调试）。
     */
    public int getUserChannelCount(String userId) {
        ChannelGroup userGroup = USER_CONTEXT_MAP.get(userId);
        return userGroup == null ? 0 : userGroup.size();
    }

    /**
     * 向指定用户的所有好友广播在线状态变更。
     *
     * @param userId 状态变更的用户 ID
     * @param status 新状态值（1=在线 2=忙碌 3=离线）
     */
    public void broadcastOnlineStatus(String userId, Integer status) {
        if (StringTools.isEmpty(userId)) {
            return;
        }
        // 获取用户的所有好友
        List<String> contactList = redisComponet.getUserContactList(userId);
        if (contactList == null || contactList.isEmpty()) {
            return;
        }
        // 向每个好友发送在线状态变更帧
        for (String friendId : contactList) {
            if (StringTools.isEmpty(friendId)) {
                continue;
            }
            MessageSendDto statusDto = new MessageSendDto();
            statusDto.setMessageType(MessageTypeEnum.ONLINE_STATUS.getType());
            statusDto.setContactId(userId);
            statusDto.setSendUserId(userId);
            statusDto.setExtendData(status);
            // 只向在线的好友发送
            if (isUserOnline(friendId)) {
                sendRawToUser(friendId, JsonUtils.convertObj2Json(statusDto));
            }
        }
        logger.info("用户{}状态变更为{}，已广播给{}个好友", userId, status, contactList.size());
    }
}
