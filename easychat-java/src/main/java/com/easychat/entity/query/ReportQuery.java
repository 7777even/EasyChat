package com.easychat.entity.query;

/**
 * 举报管理列表查询（分页 + 过滤）
 */
public class ReportQuery extends BaseParam {

    /**
     * 举报类型 1动态 2评论 3消息（null=全部）
     */
    private Integer reportType;

    /**
     * 状态 0待处理 1已处理 2已驳回（null=全部）
     */
    private Integer status;

    /**
     * 举报理由 0色情 1暴力 2诈骗 3侵权 4其他（null=全部）
     */
    private Integer reason;

    /**
     * 举报时间起（毫秒，null=不限制）
     */
    private Long startTime;

    /**
     * 举报时间止（毫秒，null=不限制）
     */
    private Long endTime;

    public Integer getReportType() {
        return reportType;
    }

    public void setReportType(Integer reportType) {
        this.reportType = reportType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getReason() {
        return reason;
    }

    public void setReason(Integer reason) {
        this.reason = reason;
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
