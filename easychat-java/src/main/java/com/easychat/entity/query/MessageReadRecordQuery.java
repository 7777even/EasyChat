package com.easychat.entity.query;

import java.util.List;


/**
 * 消息已读/送达确认记录 参数
 */
public class MessageReadRecordQuery extends BaseParam {


    /**
     * 自增主键
     */
    private Long id;

    /**
     * 消息ID
     */
    private Long messageId;

    /**
     * 消息ID列表
     */
    private List<Long> messageIdList;

    /**
     * 确认用户ID
     */
    private String userId;

    /**
     * 联系人ID
     */
    private String contactId;

    /**
     * 联系人类型
     */
    private Integer contactType;

    /**
     * ack类型 2:已送达 3:已读
     */
    private Integer ackType;

    /**
     * ack类型列表
     */
    private List<Integer> ackTypeList;


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

    public List<Long> getMessageIdList() {
        return messageIdList;
    }

    public void setMessageIdList(List<Long> messageIdList) {
        this.messageIdList = messageIdList;
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

    public List<Integer> getAckTypeList() {
        return ackTypeList;
    }

    public void setAckTypeList(List<Integer> ackTypeList) {
        this.ackTypeList = ackTypeList;
    }
}
