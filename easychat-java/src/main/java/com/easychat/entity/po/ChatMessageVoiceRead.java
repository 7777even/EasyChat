package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 语音消息未播放状态（每接收方独立）。
 *
 * <p><b>为什么是旁挂表而不是 {@code chat_message} 的列</b>：
 * 「谁播了」是 <b>per-receiver</b> 的状态。若加列到 {@code chat_message}，
 * A 播放自己收到的语音会污染 B 看到的同一行；且一个会话里常有多条语音，
 * 加列无法表达「哪几条没播」。详见 openspec design ADR-001。
 *
 * <p><b>字段初始值说明</b>：本 PO 的字段<b>一律不设 Java 初始值</b>
 * ——{@code ChatMessageVoiceReadMapper.xml#insertOrUpdate} 用
 * {@code <if test="bean.xxx != null">} 动态拼列，若这里给 {@code isRead} 写 {@code = 0}，
 * 则「只想更新 readTime」的调用会把 isRead 也一并写入，产生难以定位的串列 bug。
 * 默认值由 DDL 的 {@code NOT NULL DEFAULT 0} 承担（AGENTS §6.4-9）。
 *
 * @since 2026-10-03 位置消息与语音消息接通（openspec/changes/2026-10-03-location-and-voice-message）
 */
public class ChatMessageVoiceRead implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 语音消息 id（chat_message.message_id） */
    private Long messageId;

    /** 接收方 user_id（播放者） */
    private String userId;

    /** 播放状态 0 未播放 / 1 已播放 */
    private Integer isRead;

    /** 播放时间（毫秒），未播放为 null */
    private Long readTime;

    /** 状态行创建时间（毫秒）；播放后不变，语义不同于 readTime */
    private Long createTime;

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getIsRead() {
        return isRead;
    }

    public void setIsRead(Integer isRead) {
        this.isRead = isRead;
    }

    public Long getReadTime() {
        return readTime;
    }

    public void setReadTime(Long readTime) {
        this.readTime = readTime;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    @Override
    public String toString() {
        return "ChatMessageVoiceRead{messageId=" + messageId
                + ", userId='" + userId + '\''
                + ", isRead=" + isRead
                + ", readTime=" + readTime
                + '}';
    }
}