package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 管理端通话记录列表行（call_log LEFT JOIN 双方昵称 / 群名）
 */
public class AdminCallLogVO implements Serializable {

    /**
     * 记录 ID
     */
    private Long id;

    /**
     * 1=单聊 2=群呼
     */
    private Integer callType;

    /**
     * 1=音频 2=音视频
     */
    private Integer mediaType;

    /**
     * 1已接 2未接 3拒接 4取消 5忙线
     */
    private Integer status;

    /**
     * 发起方 userId
     */
    private String callerId;

    /**
     * 发起方昵称（LEFT JOIN user_info，用户已注销时为 null）
     */
    private String callerNickName;

    /**
     * 单聊对方 userId（群呼时为 null）
     */
    private String peerId;

    /**
     * 单聊对方昵称（用户已注销时为 null）
     */
    private String peerNickName;

    /**
     * 群呼群组 ID（单聊时为 null）
     */
    private String groupId;

    /**
     * 群呼群名（LEFT JOIN group_info，群已解散时为 null）
     */
    private String groupNickName;

    /**
     * 通话开始时间（毫秒，可为 null）
     */
    private Long startTime;

    /**
     * 通话结束时间（毫秒，可为 null）
     */
    private Long endTime;

    /**
     * 通话时长（毫秒，end_time - start_time 计算列；任一端为 null 时为 null）
     */
    private Long durationMs;

    /**
     * 参与人数
     */
    private Integer participantCount;

    /**
     * 记录创建时间（毫秒）
     */
    private Long createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getCallType() {
        return callType;
    }

    public void setCallType(Integer callType) {
        this.callType = callType;
    }

    public Integer getMediaType() {
        return mediaType;
    }

    public void setMediaType(Integer mediaType) {
        this.mediaType = mediaType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public String getCallerNickName() {
        return callerNickName;
    }

    public void setCallerNickName(String callerNickName) {
        this.callerNickName = callerNickName;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getPeerNickName() {
        return peerNickName;
    }

    public void setPeerNickName(String peerNickName) {
        this.peerNickName = peerNickName;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupNickName() {
        return groupNickName;
    }

    public void setGroupNickName(String groupNickName) {
        this.groupNickName = groupNickName;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getParticipantCount() {
        return participantCount;
    }

    public void setParticipantCount(Integer participantCount) {
        this.participantCount = participantCount;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
