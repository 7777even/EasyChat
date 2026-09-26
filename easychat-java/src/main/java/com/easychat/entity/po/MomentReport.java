package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 内容举报（朋友圈动态 / 评论）
 */
public class MomentReport implements Serializable {

    /**
     * ID
     */
    private Long id;

    /**
     * 被举报动态ID
     */
    private Long momentId;

    /**
     * 被举报评论ID
     */
    private Long commentId;

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
     * 处理人
     */
    private String handleUserId;

    /**
     * 举报时间毫秒
     */
    private Long createTime;

    /**
     * 处理时间毫秒
     */
    private Long handleTime;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setMomentId(Long momentId) {
        this.momentId = momentId;
    }

    public Long getMomentId() {
        return this.momentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public Long getCommentId() {
        return this.commentId;
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

    public void setHandleUserId(String handleUserId) {
        this.handleUserId = handleUserId;
    }

    public String getHandleUserId() {
        return this.handleUserId;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }

    public void setHandleTime(Long handleTime) {
        this.handleTime = handleTime;
    }

    public Long getHandleTime() {
        return this.handleTime;
    }
}
