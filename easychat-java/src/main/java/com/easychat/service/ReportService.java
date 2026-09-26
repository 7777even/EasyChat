package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;

/**
 * 内容举报：朋友圈动态 / 评论 / 聊天消息
 */
public interface ReportService {

    /**
     * 举报朋友圈动态或评论（momentId 与 commentId 二选一）
     */
    void reportMoment(Long momentId, Long commentId, Integer reason, String description, TokenUserInfoDto user);

    /**
     * 举报聊天消息
     */
    void reportMessage(Long messageId, Integer reason, String description, TokenUserInfoDto user);
}
