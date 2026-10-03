package com.easychat.entity.query;

import java.io.Serializable;
import java.util.Arrays;

/**
 * {@link com.easychat.entity.po.ChatMessageVoiceRead} 的查询条件。
 *
 * @since 2026-10-03 位置消息与语音消息接通
 */
public class ChatMessageVoiceReadQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 语音消息 id */
    private Long messageId;

    /** 接收方 user_id（播放者） */
    private String userId;

    /** 批量查询用：消息 id 列表（loadVoiceRead 的 IN 查询） */
    private Long[] messageIdList;

    /** 播放状态筛选 0 未播放 / 1 已播放；null 表示不限 */
    private Integer isRead;

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long[] getMessageIdList() {
        return messageIdList;
    }

    public void setMessageIdList(Long[] messageIdList) {
        this.messageIdList = messageIdList;
    }

    public void setMessageIdList(java.util.Collection<Long> ids) {
        this.messageIdList = ids == null ? null : ids.toArray(new Long[0]);
    }

    public Integer getIsRead() {
        return isRead;
    }

    public void setIsRead(Integer isRead) {
        this.isRead = isRead;
    }

    @Override
    public String toString() {
        return "ChatMessageVoiceReadQuery{messageId=" + messageId
                + ", userId='" + userId + '\''
                + ", messageIdList=" + Arrays.toString(messageIdList)
                + ", isRead=" + isRead
                + '}';
    }
}