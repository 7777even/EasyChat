package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 管理端举报详情（列表行 + 被举报内容全文 + 双方信息）
 */
public class AdminReportDetailVO implements Serializable {

    private Long id;
    private Integer reportType;
    private Long targetId;
    private String reportUserId;
    private String reportUserName;
    private Integer reason;
    private String description;
    private Integer status;
    private String handleUserId;
    private Long handleTime;
    private String handleNote;
    private Integer handleAction;
    private Long createTime;

    /**
     * 被举报内容全文
     */
    private String content;

    /**
     * 发布者ID
     */
    private String publisherId;

    /**
     * 发布者昵称
     */
    private String publisherNickName;

    /**
     * 举报人昵称
     */
    private String reporterNickName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getReportType() {
        return reportType;
    }

    public void setReportType(Integer reportType) {
        this.reportType = reportType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getReportUserId() {
        return reportUserId;
    }

    public void setReportUserId(String reportUserId) {
        this.reportUserId = reportUserId;
    }

    public String getReportUserName() {
        return reportUserName;
    }

    public void setReportUserName(String reportUserName) {
        this.reportUserName = reportUserName;
    }

    public Integer getReason() {
        return reason;
    }

    public void setReason(Integer reason) {
        this.reason = reason;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getHandleUserId() {
        return handleUserId;
    }

    public void setHandleUserId(String handleUserId) {
        this.handleUserId = handleUserId;
    }

    public Long getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Long handleTime) {
        this.handleTime = handleTime;
    }

    public String getHandleNote() {
        return handleNote;
    }

    public void setHandleNote(String handleNote) {
        this.handleNote = handleNote;
    }

    public Integer getHandleAction() {
        return handleAction;
    }

    public void setHandleAction(Integer handleAction) {
        this.handleAction = handleAction;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(String publisherId) {
        this.publisherId = publisherId;
    }

    public String getPublisherNickName() {
        return publisherNickName;
    }

    public void setPublisherNickName(String publisherNickName) {
        this.publisherNickName = publisherNickName;
    }

    public String getReporterNickName() {
        return reporterNickName;
    }

    public void setReporterNickName(String reporterNickName) {
        this.reporterNickName = reporterNickName;
    }
}
