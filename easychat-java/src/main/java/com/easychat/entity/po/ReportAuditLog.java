package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 举报处置审计日志（每条处置动作追加一条，不可变，作为审计源）
 */
public class ReportAuditLog implements Serializable {

    /**
     * ID
     */
    private Long id;

    /**
     * 关联举报记录ID（moment_report / message_report 的 id）
     */
    private Long reportId;

    /**
     * 举报类型 1动态 2评论 3消息
     */
    private Integer reportType;

    /**
     * 被举报对象ID（moment_id / comment_id / message_id）
     */
    private Long targetId;

    /**
     * 处理管理员ID
     */
    private String adminId;

    /**
     * 处置结论 1已处理 2已驳回
     */
    private Integer action;

    /**
     * 处置动作 0仅记录 1删内容 2封禁发布者
     */
    private Integer handleAction;

    /**
     * 处理备注
     */
    private String handleNote;

    /**
     * 处理时间毫秒
     */
    private Long createTime;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Long getReportId() {
        return this.reportId;
    }

    public void setReportType(Integer reportType) {
        this.reportType = reportType;
    }

    public Integer getReportType() {
        return this.reportType;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getTargetId() {
        return this.targetId;
    }

    public void setAdminId(String adminId) {
        this.adminId = adminId;
    }

    public String getAdminId() {
        return this.adminId;
    }

    public void setAction(Integer action) {
        this.action = action;
    }

    public Integer getAction() {
        return this.action;
    }

    public void setHandleAction(Integer handleAction) {
        this.handleAction = handleAction;
    }

    public Integer getHandleAction() {
        return this.handleAction;
    }

    public void setHandleNote(String handleNote) {
        this.handleNote = handleNote;
    }

    public String getHandleNote() {
        return this.handleNote;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }
}
