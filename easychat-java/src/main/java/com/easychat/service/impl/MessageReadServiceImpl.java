package com.easychat.service.impl;

import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.MessageStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.MessageReadRecord;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.MessageReadRecordQuery;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.MessageReadRecordMapper;
import com.easychat.service.MessageReadService;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("messageReadService")
public class MessageReadServiceImpl implements MessageReadService {

    private static final Logger logger = LoggerFactory.getLogger(MessageReadServiceImpl.class);

    @Resource
    private MessageReadRecordMapper<MessageReadRecord, MessageReadRecordQuery> messageReadRecordMapper;

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private ChannelContextUtils channelContextUtils;

    @Override
    public void markDelivered(String userId, Long messageId) {
        markAck(userId, messageId, MessageStatusEnum.DELIVERED.getStatus());
    }

    @Override
    public void markRead(String userId, String contactId, Integer contactType, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        for (Long messageId : messageIds) {
            markAck(userId, messageId, MessageStatusEnum.READ.getStatus());
        }
        logger.info("markRead userId={}, contactId={}, count={}", userId, contactId, messageIds.size());
    }

    @Override
    public Map<Long, Integer> batchGetAckType(List<Long> messageIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return result;
        }
        MessageReadRecordQuery query = new MessageReadRecordQuery();
        query.setMessageIdList(messageIds);
        List<MessageReadRecord> records = messageReadRecordMapper.selectList(query);
        if (records == null || records.isEmpty()) {
            return result;
        }
        // 每个 messageId 取最大 ackType
        Map<Long, List<MessageReadRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(MessageReadRecord::getMessageId));
        for (Map.Entry<Long, List<MessageReadRecord>> entry : grouped.entrySet()) {
            int maxAckType = entry.getValue().stream()
                    .map(MessageReadRecord::getAckType)
                    .max(Integer::compareTo)
                    .orElse(0);
            result.put(entry.getKey(), maxAckType);
        }
        return result;
    }

    @Override
    public List<String> getReadUserList(Long messageId) {
        MessageReadRecordQuery query = new MessageReadRecordQuery();
        query.setMessageId(messageId);
        query.setAckType(MessageStatusEnum.READ.getStatus());
        List<MessageReadRecord> records = messageReadRecordMapper.selectList(query);
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }
        return records.stream().map(MessageReadRecord::getUserId).distinct().collect(Collectors.toList());
    }

    @Override
    public void batchAck(TokenUserInfoDto user, List<Long> messageIds, Integer ackType) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        String userId = user.getUserId();
        List<MessageReadRecord> records = new ArrayList<>();
        // 查询消息详情，填充 contactId / contactType
        ChatMessageQuery chatMessageQuery = new ChatMessageQuery();
        chatMessageQuery.setMessageIdLongList(messageIds);
        List<ChatMessage> chatMessages = chatMessageMapper.selectList(chatMessageQuery);
        Map<Long, ChatMessage> msgMap = new HashMap<>();
        for (ChatMessage msg : chatMessages) {
            msgMap.put(msg.getMessageId(), msg);
        }

        List<ChatMessage> toNotifySender = new ArrayList<>();
        for (Long messageId : messageIds) {
            MessageReadRecord record = new MessageReadRecord();
            record.setMessageId(messageId);
            record.setUserId(userId);
            record.setAckType(ackType);
            ChatMessage msg = msgMap.get(messageId);
            if (msg != null) {
                record.setContactId(msg.getContactId());
                record.setContactType(msg.getContactType());
                // 不给自己发回执 且 不是自己发的消息才通知
                if (!userId.equals(msg.getSendUserId())) {
                    toNotifySender.add(msg);
                }
            }
            records.add(record);
        }
        if (!records.isEmpty()) {
            messageReadRecordMapper.insertOrUpdateBatch(records);
        }
        // 通知消息发送方：ack_type 已更新
        for (ChatMessage msg : toNotifySender) {
            channelContextUtils.sendAckNotify(msg.getSendUserId(), userId,
                    msg.getMessageId(), msg.getContactType(), ackType);
        }
        logger.info("batchAck userId={}, ackType={}, count={}", userId, ackType, messageIds.size());
    }

    /* ============================================================
     *  私有方法
     * ============================================================ */

    private void markAck(String userId, Long messageId, Integer ackType) {
        if (messageId == null || StringTools.isEmpty(userId)) {
            return;
        }
        // 查询消息获取 contactId/contactType
        ChatMessage chatMessage = chatMessageMapper.selectByMessageId(messageId);
        if (chatMessage == null) {
            return;
        }
        MessageReadRecord record = new MessageReadRecord();
        record.setMessageId(messageId);
        record.setUserId(userId);
        record.setAckType(ackType);
        record.setContactId(chatMessage.getContactId());
        record.setContactType(chatMessage.getContactType());
        messageReadRecordMapper.insertOrUpdate(record);

        // 非自己发的消息，通知发送方 ack_type 已更新
        if (!userId.equals(chatMessage.getSendUserId())) {
            channelContextUtils.sendAckNotify(chatMessage.getSendUserId(), userId,
                    messageId, chatMessage.getContactType(), ackType);
        }
    }
}
