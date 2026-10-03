package com.easychat.mappers;

import com.easychat.entity.po.ChatMessageVoiceRead;
import com.easychat.entity.query.ChatMessageVoiceReadQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 语音消息未播放状态 数据库操作接口。
 *
 * <p>表 {@code chat_message_voice_read} 由 migration-012 建出。
 *
 * @since 2026-10-03 位置消息与语音消息接通
 */
public interface ChatMessageVoiceReadMapper extends BaseMapper<ChatMessageVoiceRead, ChatMessageVoiceReadQuery> {

    /**
     * 标记为已播放（复合主键 message_id + user_id 定位）。
     *
     * <p>存在才更新：行由「接收方首次渲染该语音」时 upsert 建立，
     * 直接 update 可能影响 0 行（此时调用方应先 upsert）。
     *
     * @param bean 至少包含 messageId / userId / isRead / readTime
     * @return 影响行数
     */
    Integer markRead(@Param("bean") ChatMessageVoiceRead bean);

    /**
     * 批量查「已播放」的 message_id 列表（仅限指定用户）。
     *
     * <p>供前端渲染气泡红点：返回该用户在这些消息中已播过的 id。
     *
     * @param query 必须含 userId 与 messageIdList
     * @return 已播放的 message_id 列表
     */
    List<Long> selectReadMessageIds(@Param("query") ChatMessageVoiceReadQuery query);

    /**
     * 按复合主键查单行（message_id + user_id）。
     *
     * <p>XML 中已定义该语句，故在此显式声明，避免
     * {@code verify_mapper_params.mjs} 报「XML 有语句在接口中无声明」。
     *
     * @param messageId 语音消息 id
     * @param userId    接收方 user_id
     * @return 状态行；不存在返回 null
     */
    ChatMessageVoiceRead selectByMessageIdAndUserId(@Param("messageId") Long messageId,
                                                   @Param("userId") String userId);
}