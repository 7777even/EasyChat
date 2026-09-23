package com.easychat.entity.po;

import java.io.Serializable;
import java.util.Date;


/**
 * 消息已读/送达确认记录
 */
public class MessageReadRecord implements Serializable {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 关联 chat_message.message_id
     */
    private Long messageId;

    /**
     * 确认已读/已送达的用户 ID
     */
    private String userId;

    /**
     * 所属会话联系人 ID（群 ID 或对方用户 ID）
     */
    private String contactId;

    /**
     * 联系人类型 0:单聊 1:群聊
     */
    private Integer contactType;

    /**
     * 2:已送达 3:已读
     */
    private Integer ackType;

    /**
     * 确认时间
     */
    private Date createTime;


    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getMessageId() {
        return this.messageId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return this.userId;
    }

    public void setContactId(String contactId) {
        this.contactId = contactId;
    }

    public String getContactId() {
        return this.contactId;
    }

    public void setContactType(Integer contactType) {
        this.contactType = contactType;
    }

    public Integer getContactType() {
        return this.contactType;
    }

    public void setAckType(Integer ackType) {
        this.ackType = ackType;
    }

    public Integer getAckType() {
        return this.ackType;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getCreateTime() {
        return this.createTime;
    }

    @Override
    public String toString() {
        return "id:" + (id == null ? "空" : id) + "，messageId:" + (messageId == null ? "空" : messageId) + "，userId:" + (userId == null ? "空" : userId) + "，contactId:" + (contactId == null ? "空" : contactId) + "，contactType:" + (contactType == null ? "空" : contactType) + "，ackType:" + (ackType == null ? "空" : ackType);
    }
}
