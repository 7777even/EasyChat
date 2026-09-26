package com.easychat.controller;

import com.easychat.annotation.GlobalInterceptor;
import com.easychat.controller.ABaseController;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.query.ReportAuditQuery;
import com.easychat.entity.query.ReportQuery;
import com.easychat.entity.vo.AdminReportDetailVO;
import com.easychat.entity.vo.AdminReportVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.ReportAuditLogVO;
import com.easychat.entity.vo.Result;
import com.easychat.service.AdminReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;

@RestController("adminReportController")
@RequestMapping("/admin/report")
public class AdminReportController extends ABaseController {

    @Resource
    private AdminReportService adminReportService;

    /**
     * 举报列表（分页 + 过滤）
     */
    @PostMapping("/loadReport")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO<AdminReportVO>> loadReport(ReportQuery query) {
        return success(adminReportService.loadReport(query));
    }

    /**
     * 举报详情
     */
    @PostMapping("/getReportDetail")
    @GlobalInterceptor(checkAdmin = true)
    public Result<AdminReportDetailVO> getReportDetail(@NotNull Long id,
                                                        @NotNull Integer reportType,
                                                        HttpServletRequest request) {
        return success(adminReportService.getReportDetail(id, reportType));
    }

    /**
     * 处置举报（已处理/已驳回 + 可选动作），并写入审计日志
     */
    @PostMapping("/dealReport")
    @GlobalInterceptor(checkAdmin = true)
    public Result<Void> dealReport(@NotNull Long id,
                                   @NotNull Integer reportType,
                                   @NotNull Integer status,
                                   Integer handleAction,
                                   String handleNote,
                                   HttpServletRequest request) {
        TokenUserInfoDto admin = getTokenUserInfo(request);
        adminReportService.dealReport(id, reportType, status, handleAction, handleNote, admin);
        return success();
    }

    /**
     * 处置审计日志列表（分页 + 过滤）
     */
    @PostMapping("/loadAuditLog")
    @GlobalInterceptor(checkAdmin = true)
    public Result<PaginationResultVO<ReportAuditLogVO>> loadAuditLog(ReportAuditQuery query) {
        return success(adminReportService.loadAuditLog(query));
    }
}
