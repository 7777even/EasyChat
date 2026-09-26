package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 消息举报（聊天内容）
 */
public class MessageReport implements Serializable {

    /**
     * ID
     */
    private Long id;

    /**
     * 被举报消息ID
     */
    private Long messageId;

    /**
     * 举报人
     */
    private String reportUserId;

    /**
     * 0色情 1暴力 2诈骗 3侵权 4其他
     */
    private Integer reason;

    /**
     * 补充说明
     */
    private String description;

    /**
     * 0待处理 1已处理 2已驳回
     */
    private Integer status;

    /**
     * 举报时间毫秒
     */
    private Long createTime;

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

    public void setReportUserId(String reportUserId) {
        this.reportUserId = reportUserId;
    }

    public String getReportUserId() {
        return this.reportUserId;
    }

    public void setReason(Integer reason) {
        this.reason = reason;
    }

    public Integer getReason() {
        return this.reason;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDescription() {
        return this.description;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }
}
