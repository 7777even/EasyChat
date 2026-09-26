package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 举报处置审计日志视图
 */
public class ReportAuditLogVO implements Serializable {

    private Long id;
    private Long reportId;
    private Integer reportType;
    private Long targetId;
    private String adminId;
    private Integer action;
    private Integer handleAction;
    private String handleNote;
    private Long createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
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

    public String getAdminId() {
        return adminId;
    }

    public void setAdminId(String adminId) {
        this.adminId = adminId;
    }

    public Integer getAction() {
        return action;
    }

    public void setAction(Integer action) {
        this.action = action;
    }

    public Integer getHandleAction() {
        return handleAction;
    }

    public void setHandleAction(Integer handleAction) {
        this.handleAction = handleAction;
    }

    public String getHandleNote() {
        return handleNote;
    }

    public void setHandleNote(String handleNote) {
        this.handleNote = handleNote;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
