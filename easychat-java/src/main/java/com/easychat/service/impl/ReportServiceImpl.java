package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.MessageReport;
import com.easychat.entity.po.Moment;
import com.easychat.entity.po.MomentComment;
import com.easychat.entity.po.MomentReport;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.MomentCommentQuery;
import com.easychat.entity.query.MomentQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.MomentCommentMapper;
import com.easychat.mappers.MomentMapper;
import com.easychat.mappers.MomentReportMapper;
import com.easychat.mappers.MessageReportMapper;
import com.easychat.service.ReportService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service("reportService")
public class ReportServiceImpl implements ReportService {

    @Resource
    private MomentMapper<Moment, MomentQuery> momentMapper;
    @Resource
    private MomentCommentMapper<MomentComment, MomentCommentQuery> momentCommentMapper;
    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;
    @Resource
    private MomentReportMapper momentReportMapper;
    @Resource
    private MessageReportMapper messageReportMapper;

    @Override
    public void reportMoment(Long momentId, Long commentId, Integer reason, String description, TokenUserInfoDto user) {
        if (commentId != null) {
            MomentComment comment = momentCommentMapper.selectById(commentId);
            if (comment == null || comment.getStatus() == null || comment.getStatus() == 0) {
                throw new BusinessException(ResponseCodeEnum.CODE_2501);
            }
        } else if (momentId != null) {
            Moment moment = momentMapper.selectById(momentId);
            if (moment == null) {
                throw new BusinessException(ResponseCodeEnum.CODE_2501);
            }
        } else {
            throw new BusinessException(ResponseCodeEnum.CODE_2501);
        }
        // 幂等：同一举报人对同一对象、待处理（status=0）已存在则直接返回
        Integer cnt = momentReportMapper.countPending(user.getUserId(), momentId, commentId);
        if (cnt != null && cnt > 0) {
            return;
        }
        MomentReport report = new MomentReport();
        report.setMomentId(momentId);
        report.setCommentId(commentId);
        report.setReportUserId(user.getUserId());
        report.setReason(reason);
        report.setDescription(description);
        report.setStatus(0);
        report.setCreateTime(System.currentTimeMillis());
        momentReportMapper.insert(report);
    }

    @Override
    public void reportMessage(Long messageId, Integer reason, String description, TokenUserInfoDto user) {
        ChatMessageQuery msgQuery = new ChatMessageQuery();
        msgQuery.setMessageId(messageId);
        Integer msgCnt = chatMessageMapper.selectCount(msgQuery);
        if (msgCnt == null || msgCnt == 0) {
            throw new BusinessException(ResponseCodeEnum.CODE_2201);
        }
        // 幂等：同一举报人对同一消息、待处理（status=0）已存在则直接返回
        Integer cnt = messageReportMapper.countPending(user.getUserId(), messageId);
        if (cnt != null && cnt > 0) {
            return;
        }
        MessageReport report = new MessageReport();
        report.setMessageId(messageId);
        report.setReportUserId(user.getUserId());
        report.setReason(reason);
        report.setDescription(description);
        report.setStatus(0);
        report.setCreateTime(System.currentTimeMillis());
        messageReportMapper.insert(report);
    }
}
