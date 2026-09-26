package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.query.ReportAuditQuery;
import com.easychat.entity.query.ReportQuery;
import com.easychat.entity.vo.AdminReportDetailVO;
import com.easychat.entity.vo.AdminReportVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.ReportAuditLogVO;

/**
 * 举报处理与管理端审计
 */
public interface AdminReportService {

    /**
     * 分页查询举报列表（跨朋友圈动态/评论/聊天消息统一视图）
     */
    PaginationResultVO<AdminReportVO> loadReport(ReportQuery query);

    /**
     * 举报详情（含被举报内容全文与举报人/发布者信息）
     */
    AdminReportDetailVO getReportDetail(Long reportId, Integer reportType);

    /**
     * 处置举报：置状态并可选执行删内容/封禁发布者，并写入审计日志
     */
    void dealReport(Long reportId, Integer reportType, Integer status,
                    Integer handleAction, String handleNote, TokenUserInfoDto admin);

    /**
     * 分页查询处置审计日志
     */
    PaginationResultVO<ReportAuditLogVO> loadAuditLog(ReportAuditQuery query);
}
