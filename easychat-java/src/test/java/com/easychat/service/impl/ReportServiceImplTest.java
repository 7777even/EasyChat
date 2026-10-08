package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.Moment;
import com.easychat.entity.po.MomentComment;
import com.easychat.entity.po.MomentReport;
import com.easychat.entity.po.MessageReport;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.MomentCommentQuery;
import com.easychat.entity.query.MomentQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.MomentCommentMapper;
import com.easychat.mappers.MomentMapper;
import com.easychat.mappers.MomentReportMapper;
import com.easychat.mappers.MessageReportMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReportServiceImpl 单元测试。
 *
 * <p>举报是内容治理的入口：对象校验（朋友圈 / 评论 / 消息必须存在且
 * 状态有效）与「同一举报人对同一对象待处理举报幂等」是其核心契约，
 * 漏一项要么误报不存在的对象、要么重复落库。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class ReportServiceImplTest {

    private static final String REPORTER = "U_reporter";
    private static final Long MOMENT_ID = 6001L;
    private static final Long COMMENT_ID = 6101L;
    private static final Long MESSAGE_ID = 6201L;
    private static final Integer REASON = 1;
    private static final String DESCRIPTION = "广告内容";

    @InjectMocks
    private ReportServiceImpl reportService;

    @Mock
    private MomentMapper<Moment, MomentQuery> momentMapper;

    @Mock
    private MomentCommentMapper<MomentComment, MomentCommentQuery> momentCommentMapper;

    @Mock
    private ChatMessageMapper<ChatMessage, com.easychat.entity.query.ChatMessageQuery> chatMessageMapper;

    @Mock
    private MomentReportMapper momentReportMapper;

    @Mock
    private MessageReportMapper messageReportMapper;

    private TokenUserInfoDto reporter() {
        TokenUserInfoDto user = new TokenUserInfoDto();
        user.setUserId(REPORTER);
        user.setNickName("举报人");
        return user;
    }

    // ======================== reportMoment：对象校验 ========================

    @Test
    public void reportMoment_commentMissing_throws2501() {
        when(momentCommentMapper.selectById(COMMENT_ID)).thenReturn(null);
        try {
            reportService.reportMoment(MOMENT_ID, COMMENT_ID, REASON, DESCRIPTION, reporter());
            fail("评论不存在应抛 2501");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2501.getCode(), e.getCode());
        }
    }

    @Test
    public void reportMoment_commentStatusZero_throws2501() {
        MomentComment comment = new MomentComment();
        comment.setId(COMMENT_ID);
        comment.setStatus(0);
        when(momentCommentMapper.selectById(COMMENT_ID)).thenReturn(comment);
        try {
            reportService.reportMoment(MOMENT_ID, COMMENT_ID, REASON, DESCRIPTION, reporter());
            fail("评论状态为 0（已删除）应抛 2501");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2501.getCode(), e.getCode());
        }
    }

    @Test
    public void reportMoment_commentStatusNull_throws2501() {
        MomentComment comment = new MomentComment();
        comment.setId(COMMENT_ID);
        comment.setStatus(null);
        when(momentCommentMapper.selectById(COMMENT_ID)).thenReturn(comment);
        try {
            reportService.reportMoment(MOMENT_ID, COMMENT_ID, REASON, DESCRIPTION, reporter());
            fail("评论状态为 null 应抛 2501");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2501.getCode(), e.getCode());
        }
    }

    @Test
    public void reportMoment_momentMissing_throws2501() {
        when(momentMapper.selectById(MOMENT_ID)).thenReturn(null);
        try {
            reportService.reportMoment(MOMENT_ID, null, REASON, DESCRIPTION, reporter());
            fail("动态不存在应抛 2501");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2501.getCode(), e.getCode());
        }
    }

    @Test
    public void reportMoment_bothTargetNull_throws2501() {
        try {
            reportService.reportMoment(null, null, REASON, DESCRIPTION, reporter());
            fail("两个目标都为空应抛 2501");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2501.getCode(), e.getCode());
        }
    }

    // ======================== reportMoment：幂等与落库 ========================

    @Test
    public void reportMoment_pendingExists_silentReturn() {
        // 同一举报人对同一评论已有待处理举报 → 直接返回，不重复落库
        MomentComment comment = new MomentComment();
        comment.setId(COMMENT_ID);
        comment.setStatus(1);
        when(momentCommentMapper.selectById(COMMENT_ID)).thenReturn(comment);
        when(momentReportMapper.countPending(REPORTER, MOMENT_ID, COMMENT_ID)).thenReturn(1);

        reportService.reportMoment(MOMENT_ID, COMMENT_ID, REASON, DESCRIPTION, reporter());

        verify(momentReportMapper, never()).insert(any(MomentReport.class));
    }

    @Test
    public void reportMoment_comment_success_persists() {
        MomentComment comment = new MomentComment();
        comment.setId(COMMENT_ID);
        comment.setStatus(1);
        when(momentCommentMapper.selectById(COMMENT_ID)).thenReturn(comment);
        when(momentReportMapper.countPending(REPORTER, MOMENT_ID, COMMENT_ID)).thenReturn(0);

        reportService.reportMoment(MOMENT_ID, COMMENT_ID, REASON, DESCRIPTION, reporter());

        ArgumentCaptor<MomentReport> captor = ArgumentCaptor.forClass(MomentReport.class);
        verify(momentReportMapper).insert(captor.capture());
        MomentReport report = captor.getValue();
        assertEquals(MOMENT_ID, report.getMomentId());
        assertEquals(COMMENT_ID, report.getCommentId());
        assertEquals(REPORTER, report.getReportUserId());
        assertEquals(REASON, report.getReason());
        assertEquals(DESCRIPTION, report.getDescription());
        assertEquals(Integer.valueOf(0), report.getStatus());
        assertNotNull("创建时间由服务端填充", report.getCreateTime());
    }

    @Test
    public void reportMoment_moment_success_persists() {
        when(momentMapper.selectById(MOMENT_ID)).thenReturn(new Moment());
        when(momentReportMapper.countPending(REPORTER, MOMENT_ID, null)).thenReturn(0);

        reportService.reportMoment(MOMENT_ID, null, REASON, DESCRIPTION, reporter());

        ArgumentCaptor<MomentReport> captor = ArgumentCaptor.forClass(MomentReport.class);
        verify(momentReportMapper).insert(captor.capture());
        assertEquals(MOMENT_ID, captor.getValue().getMomentId());
        assertEquals(REPORTER, captor.getValue().getReportUserId());
        assertEquals(Integer.valueOf(0), captor.getValue().getStatus());
    }

    // ======================== reportMessage ========================

    @Test
    public void reportMessage_messageMissing_throws2201() {
        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(0);
        try {
            reportService.reportMessage(MESSAGE_ID, REASON, DESCRIPTION, reporter());
            fail("消息不存在应抛 2201");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2201.getCode(), e.getCode());
        }
    }

    @Test
    public void reportMessage_pendingExists_silentReturn() {
        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(1);
        when(messageReportMapper.countPending(REPORTER, MESSAGE_ID)).thenReturn(1);

        reportService.reportMessage(MESSAGE_ID, REASON, DESCRIPTION, reporter());

        verify(messageReportMapper, never()).insert(any(MessageReport.class));
    }

    @Test
    public void reportMessage_success_persists() {
        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(1);
        when(messageReportMapper.countPending(REPORTER, MESSAGE_ID)).thenReturn(0);

        reportService.reportMessage(MESSAGE_ID, REASON, DESCRIPTION, reporter());

        // 查询条件锁定到目标消息
        ArgumentCaptor<ChatMessageQuery> queryCaptor = ArgumentCaptor.forClass(ChatMessageQuery.class);
        verify(chatMessageMapper).selectCount(queryCaptor.capture());
        assertEquals(MESSAGE_ID, queryCaptor.getValue().getMessageId());

        ArgumentCaptor<MessageReport> captor = ArgumentCaptor.forClass(MessageReport.class);
        verify(messageReportMapper).insert(captor.capture());
        MessageReport report = captor.getValue();
        assertEquals(MESSAGE_ID, report.getMessageId());
        assertEquals(REPORTER, report.getReportUserId());
        assertEquals(REASON, report.getReason());
        assertEquals(DESCRIPTION, report.getDescription());
        assertEquals(Integer.valueOf(0), report.getStatus());
        assertNotNull("创建时间由服务端填充", report.getCreateTime());
    }
}
