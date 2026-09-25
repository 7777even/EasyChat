package com.easychat.service;

import com.easychat.entity.vo.MomentNotifyVO;
import com.easychat.entity.vo.PaginationResultVO;

import java.util.List;

/**
 * 朋友圈通知服务：红点未读数 + 通知中心列表 + 已读标记
 */
public interface MomentNotifyService {

    /**
     * 写入一条通知并实时推送 WS 帧给收件人
     *
     * @param userId     收件人
     * @param type       0新动态 1点赞 2评论 3回复 4@
     * @param refId      关联动态 / 评论 ID
     * @param fromUserId 触发人
     * @param content    通知摘要
     */
    void pushNotify(String userId, Integer type, Long refId, String fromUserId, String content);

    /**
     * 未读通知数（朋友圈红点）
     */
    Integer getUnreadCount(String userId);

    /**
     * 通知中心列表（按时间倒序）
     */
    PaginationResultVO<MomentNotifyVO> loadNotifyList(String userId, Integer pageNo, Integer pageSize);

    /**
     * 全部标记已读
     */
    void markAllRead(String userId);

    /**
     * 按类型标记已读
     */
    void markReadByType(String userId, Integer type);

    /**
     * 单条标记已读
     */
    void markRead(String userId, Long notifyId);

    /**
     * 清空我的通知
     */
    void clearNotify(String userId);

    /**
     * 最近一条通知（供前端做 toast 文案）
     */
    List<MomentNotifyVO> loadRecentNotify(String userId, Integer limit);
}
