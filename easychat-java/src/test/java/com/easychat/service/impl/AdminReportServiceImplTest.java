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
import com.easychat.service.ChatMessageService;
import com.easychat.service.UserInfoService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminReportServiceImpl 单元测试。
 *
 * <p>举报处置是内容治理的管理端核心：「仅待处理举报可处置（2702）」、
 * 「处置动作 / 结果码白名单（2703）」、DELETE_CONTENT 的逐类型删除
 * （消息走 adminDeleteMessage 幂等删除、动态 / 评论逻辑删除 status→0）、
 * BAN_PUBLISHER 的发布者封禁，以及每次处置必落不可变审计日志。
 * 漏一项会让处置静默失效或越权处置。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AdminReportServiceImplTest {

    private static final String ADMIN_ID = "U_admin";
    private static final String REPORTER_ID = "U_reporter";
    private static final String PUBLISHER_ID = "U_publisher";
    private static final Long REPORT_ID = 7001L;
    private static final Long TARGET_ID = 7101L;
    private static final Integer REASON = 1;
    private static final String DESCRIPTION = "广告";
    private static final String HANDLE_NOTE = "同意处置";

    @InjectMocks
    private AdminReportServiceImpl adminReportService;

    @Mock
    private ReportReadMapper reportReadMapper;

    @Mock
    private ReportAuditLogMapper reportAuditLogMapper;

    @Mock
    private MomentReportMapper momentReportMapper;

    @Mock
    private MessageReportMapper messageReportMapper;

    @Mock
    private MomentMapper<Moment, MomentQuery> momentMapper;

    @Mock
    private MomentCommentMapper<MomentComment, MomentCommentQuery> momentCommentMapper;

    @Mock
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Mock
    private UserInfoService userInfoService;

    @Mock
    private ChatMessageService chatMessageService;

    // ======================== 工具方法 ========================

    private TokenUserInfoDto admin() {
        TokenUserInfoDto admin = new TokenUserInfoDto();
        admin.setUserId(ADMIN_ID);
        return admin;
    }

    private AdminReportVO reportOf(Integer reportType, Long targetId, Integer status) {
        AdminReportVO row = new AdminReportVO();
        row.setId(REPORT_ID);
        row.setReportType(reportType);
        row.setTargetId(targetId);
        row.setReportUserId(REPORTER_ID);
        row.setReportUserName("举报人甲");
        row.setReason(REASON);
        row.setDescription(DESCRIPTION);
        row.setStatus(status);
        row.setCreateTime(System.currentTimeMillis());
        return row;
    }

    /** 断言审计日志落库字段并返回捕获对象（每次处置必落一条不可变记录）。 */
    private ReportAuditLog verifyAuditInsert(Integer reportType, Long targetId,
                                             Integer action, Integer handleAction) {
        ArgumentCaptor<ReportAuditLog> captor = ArgumentCaptor.forClass(ReportAuditLog.class);
        verify(reportAuditLogMapper).insert(captor.capture());
        ReportAuditLog log = captor.getValue();
        assertEquals(REPORT_ID, log.getReportId());
        assertEquals(reportType, log.getReportType());
        assertEquals(targetId, log.getTargetId());
        assertEquals(ADMIN_ID, log.getAdminId());
        assertEquals(action, log.getAction());
        assertEquals(handleAction, log.getHandleAction());
        assertNotNull("审计日志创建时间由服务端填充", log.getCreateTime());
        return log;
    }

    // ======================== loadReport ========================

    @Test
    public void loadReport_success_defaultPageSize15() {
        when(reportReadMapper.selectReportCount(any(ReportQuery.class))).thenReturn(7);
        when(reportReadMapper.selectReportList(any(ReportQuery.class)))
                .thenReturn(Collections.singletonList(reportOf(1, TARGET_ID, 0)));

        ReportQuery query = new ReportQuery();
        PaginationResultVO<AdminReportVO> result = adminReportService.loadReport(query);

        assertEquals(Integer.valueOf(7), result.getTotalCount());
        // pageSize 未传 → 默认 15（SIZE15），页码默认 1
        assertEquals(Integer.valueOf(PageSize.SIZE15.getSize()), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertEquals(Integer.valueOf(1), result.getPageTotal());
        assertEquals(1, result.getList().size());
        // 分页参数回写查询对象
        assertNotNull(query.getSimplePage());
        assertEquals(PageSize.SIZE15.getSize(), query.getSimplePage().getPageSize());
    }

    // ======================== getReportDetail ========================

    @Test
    public void getReportDetail_missing_throws2702() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MOMENT.getCode()))
                .thenReturn(null);
        try {
            adminReportService.getReportDetail(REPORT_ID, ReportTypeEnum.MOMENT.getCode());
            fail("举报不存在应抛 2702");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2702.getCode(), e.getCode());
        }
    }

    @Test
    public void getReportDetail_moment_enrichesContentAndPublisher() {
        AdminReportVO row = reportOf(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 0);
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MOMENT.getCode()))
                .thenReturn(row);
        Moment moment = new Moment();
        moment.setId(TARGET_ID);
        moment.setUserId(PUBLISHER_ID);
        moment.setContent("动态内容");
        when(momentMapper.selectById(TARGET_ID)).thenReturn(moment);
        UserInfo publisher = new UserInfo();
        publisher.setUserId(PUBLISHER_ID);
        publisher.setNickName("发布者昵称");
        when(userInfoMapper.selectByUserId(PUBLISHER_ID)).thenReturn(publisher);

        AdminReportDetailVO detail =
                adminReportService.getReportDetail(REPORT_ID, ReportTypeEnum.MOMENT.getCode());

        // 举报人昵称优先取报告行冗余字段，不查库
        assertEquals("举报人甲", detail.getReporterNickName());
        assertEquals("动态内容", detail.getContent());
        assertEquals(PUBLISHER_ID, detail.getPublisherId());
        assertEquals("发布者昵称", detail.getPublisherNickName());
        assertEquals(REASON, detail.getReason());
        assertEquals(DESCRIPTION, detail.getDescription());
    }

    @Test
    public void getReportDetail_reporterNameFallsBackToUserLookup() {
        // 报告行未冗余举报人昵称 → 按 reportUserId 查库
        AdminReportVO row = reportOf(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 0);
        row.setReportUserName(null);
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MOMENT.getCode()))
                .thenReturn(row);
        when(momentMapper.selectById(TARGET_ID)).thenReturn(null);
        UserInfo reporter = new UserInfo();
        reporter.setUserId(REPORTER_ID);
        reporter.setNickName("举报人真名");
        when(userInfoMapper.selectByUserId(REPORTER_ID)).thenReturn(reporter);

        AdminReportDetailVO detail =
                adminReportService.getReportDetail(REPORT_ID, ReportTypeEnum.MOMENT.getCode());

        assertEquals("举报人真名", detail.getReporterNickName());
        // 目标动态已删除：内容保持 null，不抛异常
        assertNull(detail.getContent());
    }

    @Test
    public void getReportDetail_comment_enrichesFromComment() {
        AdminReportVO row = reportOf(ReportTypeEnum.COMMENT.getCode(), TARGET_ID, 0);
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.COMMENT.getCode()))
                .thenReturn(row);
        MomentComment comment = new MomentComment();
        comment.setId(TARGET_ID);
        comment.setUserId(PUBLISHER_ID);
        comment.setContent("评论内容");
        when(momentCommentMapper.selectById(TARGET_ID)).thenReturn(comment);
        UserInfo publisher = new UserInfo();
        publisher.setNickName("评论者昵称");
        when(userInfoMapper.selectByUserId(PUBLISHER_ID)).thenReturn(publisher);

        AdminReportDetailVO detail =
                adminReportService.getReportDetail(REPORT_ID, ReportTypeEnum.COMMENT.getCode());

        assertEquals("评论内容", detail.getContent());
        assertEquals(PUBLISHER_ID, detail.getPublisherId());
        assertEquals("评论者昵称", detail.getPublisherNickName());
    }

    @Test
    public void getReportDetail_message_readsByPrimaryKey() {
        AdminReportVO row = reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0);
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(row);
        // PK 读：已删消息的举报详情仍能读到原文（管理端证据链）
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(TARGET_ID);
        msg.setSendUserId(PUBLISHER_ID);
        msg.setMessageContent("消息内容");
        when(chatMessageMapper.selectByMessageId(TARGET_ID)).thenReturn(msg);
        UserInfo publisher = new UserInfo();
        publisher.setNickName("发送者昵称");
        when(userInfoMapper.selectByUserId(PUBLISHER_ID)).thenReturn(publisher);

        AdminReportDetailVO detail =
                adminReportService.getReportDetail(REPORT_ID, ReportTypeEnum.MESSAGE.getCode());

        assertEquals("消息内容", detail.getContent());
        assertEquals(PUBLISHER_ID, detail.getPublisherId());
        assertEquals("发送者昵称", detail.getPublisherNickName());
    }

    // ======================== dealReport：参数校验 ========================

    @Test
    public void dealReport_reportMissing_throws2702() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(null);
        try {
            adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                    1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());
            fail("举报不存在应抛 2702");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2702.getCode(), e.getCode());
        }
    }

    @Test
    public void dealReport_alreadyHandled_throws2702() {
        // 仅待处理（status=0）举报可处置
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 1));
        try {
            adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                    1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());
            fail("已处置举报应抛 2702");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2702.getCode(), e.getCode());
        }
    }

    @Test
    public void dealReport_statusNull_throws2703() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        try {
            adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                    null, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());
            fail("处置结果为空应抛 2703");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2703.getCode(), e.getCode());
        }
    }

    @Test
    public void dealReport_statusInvalid_throws2703() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        try {
            adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                    3, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());
            fail("非法处置结果应抛 2703");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2703.getCode(), e.getCode());
        }
    }

    @Test
    public void dealReport_actionInvalid_throws2703() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        try {
            adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                    1, 9, HANDLE_NOTE, admin());
            fail("非法处置动作应抛 2703");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2703.getCode(), e.getCode());
        }
    }

    // ======================== dealReport：消息 ========================

    @Test
    public void dealReport_message_deleteContent_deletesAndAppendsNote() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        when(chatMessageService.adminDeleteMessage(eq(TARGET_ID),
                any(TokenUserInfoDto.class))).thenReturn(true);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                1, HandleActionEnum.DELETE_CONTENT.getCode(), null, admin());

        // ADR-002：真实逻辑删除（delete_flag 置位 + 预览占位 + 20 帧）
        verify(chatMessageService).adminDeleteMessage(eq(TARGET_ID),
                any(TokenUserInfoDto.class));
        ArgumentCaptor<MessageReport> beanCaptor = ArgumentCaptor.forClass(MessageReport.class);
        verify(messageReportMapper).updateById(beanCaptor.capture(), eq(REPORT_ID));
        MessageReport updated = beanCaptor.getValue();
        assertEquals(Integer.valueOf(1), updated.getStatus());
        assertEquals(ADMIN_ID, updated.getHandleUserId());
        assertEquals(HandleActionEnum.DELETE_CONTENT.getCode(), updated.getHandleAction());
        assertNotNull(updated.getHandleTime());
        // handleNote 为空 → 直接写入删除结果
        assertEquals("已执行逻辑删除（delete_flag 置位）并推送 20 帧删除通知", updated.getHandleNote());

        ReportAuditLog log = verifyAuditInsert(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID,
                1, HandleActionEnum.DELETE_CONTENT.getCode());
        assertEquals("已执行逻辑删除（delete_flag 置位）并推送 20 帧删除通知", log.getHandleNote());
        // 删除内容动作不封禁发布者
        verify(userInfoService, never()).updateUserStatus(any(Integer.class), any(String.class));
    }

    @Test
    public void dealReport_message_deleteContent_alreadyDeleted_appendsIdempotentNote() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        // 目标消息不存在或此前已删除 → 幂等跳过
        when(chatMessageService.adminDeleteMessage(eq(TARGET_ID),
                any(TokenUserInfoDto.class))).thenReturn(false);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());

        verify(chatMessageService).adminDeleteMessage(eq(TARGET_ID),
                any(TokenUserInfoDto.class));
        ReportAuditLog log = verifyAuditInsert(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID,
                1, HandleActionEnum.DELETE_CONTENT.getCode());
        assertEquals("同意处置；目标消息不存在或此前已删除，幂等跳过", log.getHandleNote());
    }

    @Test
    public void dealReport_message_banPublisher_bansSender() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(TARGET_ID);
        msg.setSendUserId(PUBLISHER_ID);
        when(chatMessageMapper.selectByMessageId(TARGET_ID)).thenReturn(msg);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                2, HandleActionEnum.BAN_PUBLISHER.getCode(), HANDLE_NOTE, admin());

        // 封禁发布者：用户状态置 0
        verify(userInfoService).updateUserStatus(0, PUBLISHER_ID);
        ArgumentCaptor<MessageReport> beanCaptor = ArgumentCaptor.forClass(MessageReport.class);
        verify(messageReportMapper).updateById(beanCaptor.capture(), eq(REPORT_ID));
        assertEquals(Integer.valueOf(2), beanCaptor.getValue().getStatus());
        assertEquals(HandleActionEnum.BAN_PUBLISHER.getCode(), beanCaptor.getValue().getHandleAction());
        // 封禁动作不删除消息内容
        verify(chatMessageService, never()).adminDeleteMessage(any(Long.class), any(TokenUserInfoDto.class));
        ReportAuditLog log = verifyAuditInsert(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID,
                2, HandleActionEnum.BAN_PUBLISHER.getCode());
        assertEquals(HANDLE_NOTE, log.getHandleNote());
    }

    @Test
    public void dealReport_message_banPublisher_messageMissing_noBan() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MESSAGE.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 0));
        when(chatMessageMapper.selectByMessageId(TARGET_ID)).thenReturn(null);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MESSAGE.getCode(),
                1, HandleActionEnum.BAN_PUBLISHER.getCode(), HANDLE_NOTE, admin());

        // 消息已删除：无法取到发送者，跳过封禁，但处置记录与审计日志仍落库
        verify(userInfoService, never()).updateUserStatus(any(Integer.class), any(String.class));
        verify(messageReportMapper).updateById(any(MessageReport.class), eq(REPORT_ID));
        verifyAuditInsert(ReportTypeEnum.MESSAGE.getCode(), TARGET_ID, 1,
                HandleActionEnum.BAN_PUBLISHER.getCode());
    }

    // ======================== dealReport：朋友圈动态 ========================

    @Test
    public void dealReport_moment_deleteContent_deletesMoment() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MOMENT.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 0));
        Moment moment = new Moment();
        moment.setId(TARGET_ID);
        moment.setUserId(PUBLISHER_ID);
        moment.setContent("动态内容");
        moment.setStatus(1);
        when(momentMapper.selectById(TARGET_ID)).thenReturn(moment);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MOMENT.getCode(),
                1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());

        // 动态逻辑删除：status → 0
        ArgumentCaptor<Moment> beanCaptor = ArgumentCaptor.forClass(Moment.class);
        verify(momentMapper).updateById(beanCaptor.capture(), eq(TARGET_ID));
        assertEquals(Integer.valueOf(0), beanCaptor.getValue().getStatus());
        // 处置记录落 moment_report 表
        ArgumentCaptor<MomentReport> reportCaptor = ArgumentCaptor.forClass(MomentReport.class);
        verify(momentReportMapper).updateById(reportCaptor.capture(), eq(REPORT_ID));
        assertEquals(Integer.valueOf(1), reportCaptor.getValue().getStatus());
        assertEquals(HANDLE_NOTE, reportCaptor.getValue().getHandleNote());
        // 删除动作不封禁发布者
        verify(userInfoService, never()).updateUserStatus(any(Integer.class), any(String.class));
        verifyAuditInsert(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 1,
                HandleActionEnum.DELETE_CONTENT.getCode());
    }

    @Test
    public void dealReport_moment_banPublisher_bansPublisher() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MOMENT.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 0));
        Moment moment = new Moment();
        moment.setId(TARGET_ID);
        moment.setUserId(PUBLISHER_ID);
        when(momentMapper.selectById(TARGET_ID)).thenReturn(moment);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MOMENT.getCode(),
                2, HandleActionEnum.BAN_PUBLISHER.getCode(), HANDLE_NOTE, admin());

        verify(userInfoService).updateUserStatus(0, PUBLISHER_ID);
        verifyAuditInsert(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 2,
                HandleActionEnum.BAN_PUBLISHER.getCode());
        // 封禁动作不删除动态
        verify(momentMapper, never()).updateById(any(Moment.class), any(Long.class));
    }

    @Test
    public void dealReport_moment_deleteContent_momentMissing_skipsDelete() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.MOMENT.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 0));
        when(momentMapper.selectById(TARGET_ID)).thenReturn(null);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.MOMENT.getCode(),
                1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());

        // 动态已删除：跳过删除，但处置记录与审计日志仍落库
        verify(momentMapper, never()).updateById(any(Moment.class), any(Long.class));
        verify(momentReportMapper).updateById(any(MomentReport.class), eq(REPORT_ID));
        verifyAuditInsert(ReportTypeEnum.MOMENT.getCode(), TARGET_ID, 1,
                HandleActionEnum.DELETE_CONTENT.getCode());
    }

    // ======================== dealReport：评论 ========================

    @Test
    public void dealReport_comment_deleteContent_deletesComment() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.COMMENT.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.COMMENT.getCode(), TARGET_ID, 0));
        MomentComment comment = new MomentComment();
        comment.setId(TARGET_ID);
        comment.setUserId(PUBLISHER_ID);
        comment.setContent("评论内容");
        comment.setStatus(1);
        when(momentCommentMapper.selectById(TARGET_ID)).thenReturn(comment);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.COMMENT.getCode(),
                1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());

        // 评论逻辑删除：status → 0
        ArgumentCaptor<MomentComment> beanCaptor = ArgumentCaptor.forClass(MomentComment.class);
        verify(momentCommentMapper).updateById(beanCaptor.capture(), eq(TARGET_ID));
        assertEquals(Integer.valueOf(0), beanCaptor.getValue().getStatus());
        verify(momentReportMapper).updateById(any(MomentReport.class), eq(REPORT_ID));
        verify(userInfoService, never()).updateUserStatus(any(Integer.class), any(String.class));
        verifyAuditInsert(ReportTypeEnum.COMMENT.getCode(), TARGET_ID, 1,
                HandleActionEnum.DELETE_CONTENT.getCode());
    }

    @Test
    public void dealReport_comment_banPublisher_bansCommenter() {
        when(reportReadMapper.selectReportById(REPORT_ID, ReportTypeEnum.COMMENT.getCode()))
                .thenReturn(reportOf(ReportTypeEnum.COMMENT.getCode(), TARGET_ID, 0));
        MomentComment comment = new MomentComment();
        comment.setId(TARGET_ID);
        comment.setUserId(PUBLISHER_ID);
        when(momentCommentMapper.selectById(TARGET_ID)).thenReturn(comment);

        adminReportService.dealReport(REPORT_ID, ReportTypeEnum.COMMENT.getCode(),
                1, HandleActionEnum.BAN_PUBLISHER.getCode(), HANDLE_NOTE, admin());

        verify(userInfoService).updateUserStatus(0, PUBLISHER_ID);
        verifyAuditInsert(ReportTypeEnum.COMMENT.getCode(), TARGET_ID, 1,
                HandleActionEnum.BAN_PUBLISHER.getCode());
    }

    // ======================== loadAuditLog ========================

    @Test
    public void loadAuditLog_success_defaultPageSize15() {
        when(reportAuditLogMapper.selectCount(any(ReportAuditQuery.class))).thenReturn(3);
        when(reportAuditLogMapper.selectList(any(ReportAuditQuery.class)))
                .thenReturn(Collections.singletonList(new ReportAuditLogVO()));

        ReportAuditQuery query = new ReportAuditQuery();
        PaginationResultVO<ReportAuditLogVO> result = adminReportService.loadAuditLog(query);

        assertEquals(Integer.valueOf(3), result.getTotalCount());
        assertEquals(Integer.valueOf(PageSize.SIZE15.getSize()), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertNotNull(query.getSimplePage());
        assertEquals(1, result.getList().size());
    }

    // ======================== 兜底 ========================

    @Test
    public void dealReport_unknownType_fallsIntoMomentReportBranch() {
        // 未知 reportType：else 分支覆盖一切非 MESSAGE 类型 → 处置记录落 moment_report 表，
        // 评论子分支查不到目标则跳过删除，审计日志仍落库（防御性兜底，不抛异常）
        when(reportReadMapper.selectReportById(REPORT_ID, 9))
                .thenReturn(reportOf(9, TARGET_ID, 0));

        adminReportService.dealReport(REPORT_ID, 9,
                1, HandleActionEnum.DELETE_CONTENT.getCode(), HANDLE_NOTE, admin());

        verify(momentReportMapper).updateById(any(MomentReport.class), eq(REPORT_ID));
        verify(messageReportMapper, never()).updateById(any(MessageReport.class), any(Long.class));
        verifyAuditInsert(9, TARGET_ID, 1, HandleActionEnum.DELETE_CONTENT.getCode());
    }
}
