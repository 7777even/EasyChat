package com.easychat.entity.query;

/**
 * 举报处置审计日志查询（分页 + 过滤）
 */
public class ReportAuditQuery extends BaseParam {

    /**
     * 关联举报记录ID（null=不限制）
     */
    private Long reportId;

    /**
     * 举报类型 1动态 2评论 3消息（null=全部）
     */
    private Integer reportType;

    /**
     * 处理管理员ID（null=全部）
     */
    private String adminId;

    /**
     * 处置结论 1已处理 2已驳回（null=全部）
     */
    private Integer action;

    /**
     * 处理时间起（毫秒，null=不限制）
     */
    private Long startTime;

    /**
     * 处理时间止（毫秒，null=不限制）
     */
    private Long endTime;

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

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }
}
