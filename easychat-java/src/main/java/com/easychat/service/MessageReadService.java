package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.MessageReadRecord;

import java.util.List;
import java.util.Map;


/**
 * 消息已读/送达 业务接口
 */
public interface MessageReadService {

    /**
     * 用户送达确认：收到 WS 消息后调用
     *
     * @param userId    当前用户 ID
     * @param messageId 消息 ID
     */
    void markDelivered(String userId, Long messageId);

    /**
     * 用户已读确认：用户打开会话时批量调用
     *
     * @param userId      当前用户 ID
     * @param contactId   联系人 ID（群 ID 或对方用户 ID）
     * @param messageIds  待标记已读的消息 ID 列表
     * @param contactType 联系人类型
     */
    void markRead(String userId, String contactId, Integer contactType, List<Long> messageIds);

    /**
     * 批量查询消息的 ack 状态
     *
     * @param messageIds 消息 ID 列表
     * @return messageId -> ack_type 映射
     */
    Map<Long, Integer> batchGetAckType(List<Long> messageIds);

    /**
     * 单条消息的已读成员列表（用于群聊"已读成员"头像浮层）
     *
     * @param messageId 消息 ID
     * @return 已读的用户 ID 列表
     */
    List<String> getReadUserList(Long messageId);

    /**
     * 批量 ack 写入（客户端 -3 帧驱动）
     *
     * @param user       当前用户
     * @param messageIds 消息 ID 列表
     * @param ackType    2=已送达 3=已读
     */
    void batchAck(TokenUserInfoDto user, List<Long> messageIds, Integer ackType);
}
