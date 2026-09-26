package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.HandleActionEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ReportTypeEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.Moment;
import com.easychat.entity.po.MomentComment;
import com.easychat.entity.po.MomentReport;
import com.easychat.entity.po.MessageReport;
import com.easychat.entity.po.ReportAuditLog;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.MomentCommentQuery;
import com.easychat.entity.query.MomentQuery;
import com.easychat.entity.query.ReportAuditQuery;
import com.easychat.entity.query.ReportQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.AdminReportDetailVO;
import com.easychat.entity.vo.AdminReportVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.ReportAuditLogVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.MomentCommentMapper;
import com.easychat.mappers.MomentMapper;
import com.easychat.mappers.MomentReportMapper;
import com.easychat.mappers.MessageReportMapper;
import com.easychat.mappers.ReportAuditLogMapper;
import com.easychat.mappers.ReportReadMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.service.AdminReportService;
import com.easychat.service.UserInfoService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service("adminReportService")
public class AdminReportServiceImpl implements AdminReportService {

    @Resource
    private ReportReadMapper reportReadMapper;
    @Resource
    private ReportAuditLogMapper reportAuditLogMapper;
    @Resource
    private MomentReportMapper momentReportMapper;
    @Resource
    private MessageReportMapper messageReportMapper;
    @Resource
    private MomentMapper<Moment, MomentQuery> momentMapper;
    @Resource
    private MomentCommentMapper<MomentComment, MomentCommentQuery> momentCommentMapper;
    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;
    @Resource
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;
    @Resource
    private UserInfoService userInfoService;

    @Override
    public PaginationResultVO<AdminReportVO> loadReport(ReportQuery query) {
        int count = reportReadMapper.selectReportCount(query);
        int pageSize = query.getPageSize() == null ? PageSize.SIZE15.getSize() : query.getPageSize();
        SimplePage page = new SimplePage(query.getPageNo(), count, pageSize);
        query.setSimplePage(page);
        List<AdminReportVO> list = reportReadMapper.selectReportList(query);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }

    @Override
    public AdminReportDetailVO getReportDetail(Long reportId, Integer reportType) {
        AdminReportVO row = reportReadMapper.selectReportById(reportId, reportType);
        if (row == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2702);
        }
        AdminReportDetailVO detail = new AdminReportDetailVO();
        copyBase(row, detail);
        detail.setReporterNickName(row.getReportUserName() != null ? row.getReportUserName() : nickName(row.getReportUserId()));

        if (ReportTypeEnum.MOMENT.getCode().equals(reportType)) {
            Moment m = momentMapper.selectById(row.getTargetId());
            if (m != null) {
                detail.setContent(m.getContent());
                detail.setPublisherId(m.getUserId());
                detail.setPublisherNickName(nickName(m.getUserId()));
            }
        } else if (ReportTypeEnum.COMMENT.getCode().equals(reportType)) {
            MomentComment c = momentCommentMapper.selectById(row.getTargetId());
            if (c != null) {
                detail.setContent(c.getContent());
                detail.setPublisherId(c.getUserId());
                detail.setPublisherNickName(nickName(c.getUserId()));
            }
        } else if (ReportTypeEnum.MESSAGE.getCode().equals(reportType)) {
            ChatMessage msg = selectMessage(row.getTargetId());
            if (msg != null) {
                detail.setContent(msg.getMessageContent());
                detail.setPublisherId(msg.getSendUserId());
                detail.setPublisherNickName(nickName(msg.getSendUserId()));
            }
        }
        return detail;
    }

    @Override
    public void dealReport(Long reportId, Integer reportType, Integer status,
                           Integer handleAction, String handleNote, TokenUserInfoDto admin) {
        AdminReportVO row = reportReadMapper.selectReportById(reportId, reportType);
        if (row == null || (row.getStatus() != null && row.getStatus() != 0)) {
            throw new BusinessException(ResponseCodeEnum.CODE_2702);
        }
        if (status == null || (status != 1 && status != 2)) {
            throw new BusinessException(ResponseCodeEnum.CODE_2703);
        }
        HandleActionEnum action = HandleActionEnum.getByCode(handleAction);
        if (action == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_2703);
        }
        Long now = System.currentTimeMillis();

        if (ReportTypeEnum.MESSAGE.getCode().equals(reportType)) {
            MessageReport r = new MessageReport();
            applyHandle(r, status, admin.getUserId(), now, handleNote, handleAction);
            messageReportMapper.updateById(r, reportId);
            if (HandleActionEnum.DELETE_CONTENT.equals(action)) {
                // 聊天消息无删除状态位，仅记录，不物理删除
                handleNote = appendNote(handleNote, "聊天消息无删除状态位，未执行物理删除");
            }
            if (HandleActionEnum.BAN_PUBLISHER.equals(action)) {
                ChatMessage msg = selectMessage(row.getTargetId());
                if (msg != null && msg.getSendUserId() != null) {
                    userInfoService.updateUserStatus(0, msg.getSendUserId());
                }
            }
        } else {
            // 朋友圈动态(reportType=1) 与 评论(reportType=2) 均落在 moment_report 表
            MomentReport r = new MomentReport();
            applyHandle(r, status, admin.getUserId(), now, handleNote, handleAction);
            momentReportMapper.updateById(r, reportId);
            if (HandleTypeIsMoment(reportType)) {
                if (HandleActionEnum.DELETE_CONTENT.equals(action)) {
                    Moment m = momentMapper.selectById(row.getTargetId());
                    if (m != null) {
                        m.setStatus(0);
                        momentMapper.updateById(m, row.getTargetId());
                    }
                }
                if (HandleActionEnum.BAN_PUBLISHER.equals(action)) {
                    Moment m = momentMapper.selectById(row.getTargetId());
                    if (m != null && m.getUserId() != null) {
                        userInfoService.updateUserStatus(0, m.getUserId());
                    }
                }
            } else {
                if (HandleActionEnum.DELETE_CONTENT.equals(action)) {
                    MomentComment c = momentCommentMapper.selectById(row.getTargetId());
                    if (c != null) {
                        c.setStatus(0);
                        momentCommentMapper.updateById(c, row.getTargetId());
                    }
                }
                if (HandleActionEnum.BAN_PUBLISHER.equals(action)) {
                    MomentComment c = momentCommentMapper.selectById(row.getTargetId());
                    if (c != null && c.getUserId() != null) {
                        userInfoService.updateUserStatus(0, c.getUserId());
                    }
                }
            }
        }

        // 审计日志：每次处置动作追加一条不可变记录
        ReportAuditLog log = new ReportAuditLog();
        log.setReportId(reportId);
        log.setReportType(reportType);
        log.setTargetId(row.getTargetId());
        log.setAdminId(admin.getUserId());
        log.setAction(status == 1 ? 1 : 2);
        log.setHandleAction(handleAction);
        log.setHandleNote(handleNote);
        log.setCreateTime(now);
        reportAuditLogMapper.insert(log);
    }

    @Override
    public PaginationResultVO<ReportAuditLogVO> loadAuditLog(ReportAuditQuery query) {
        int count = reportAuditLogMapper.selectCount(query);
        int pageSize = query.getPageSize() == null ? PageSize.SIZE15.getSize() : query.getPageSize();
        SimplePage page = new SimplePage(query.getPageNo(), count, pageSize);
        query.setSimplePage(page);
        List<ReportAuditLogVO> list = reportAuditLogMapper.selectList(query);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }

    private void copyBase(AdminReportVO row, AdminReportDetailVO detail) {
        detail.setId(row.getId());
        detail.setReportType(row.getReportType());
        detail.setTargetId(row.getTargetId());
        detail.setReportUserId(row.getReportUserId());
        detail.setReportUserName(row.getReportUserName());
        detail.setReason(row.getReason());
        detail.setDescription(row.getDescription());
        detail.setStatus(row.getStatus());
        detail.setHandleUserId(row.getHandleUserId());
        detail.setHandleTime(row.getHandleTime());
        detail.setHandleNote(row.getHandleNote());
        detail.setHandleAction(row.getHandleAction());
        detail.setCreateTime(row.getCreateTime());
    }

    private void applyHandle(Object r, Integer status, String adminId, Long now, String handleNote, Integer handleAction) {
        if (r instanceof MomentReport) {
            MomentReport mr = (MomentReport) r;
            mr.setStatus(status);
            mr.setHandleUserId(adminId);
            mr.setHandleTime(now);
            mr.setHandleNote(handleNote);
            mr.setHandleAction(handleAction);
        } else if (r instanceof MessageReport) {
            MessageReport msgr = (MessageReport) r;
            msgr.setStatus(status);
            msgr.setHandleUserId(adminId);
            msgr.setHandleTime(now);
            msgr.setHandleNote(handleNote);
            msgr.setHandleAction(handleAction);
        }
    }

    private boolean HandleTypeIsMoment(Integer reportType) {
        return ReportTypeEnum.MOMENT.getCode().equals(reportType);
    }

    private String appendNote(String note, String extra) {
        if (note == null || note.isEmpty()) {
            return extra;
        }
        return note + "；" + extra;
    }

    private ChatMessage selectMessage(Long messageId) {
        if (messageId == null) {
            return null;
        }
        ChatMessageQuery q = new ChatMessageQuery();
        q.setMessageId(messageId);
        List<ChatMessage> list = chatMessageMapper.selectList(q);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    private String nickName(String userId) {
        if (userId == null) {
            return null;
        }
        UserInfo u = userInfoMapper.selectByUserId(userId);
        return u != null ? u.getNickName() : null;
    }
}
