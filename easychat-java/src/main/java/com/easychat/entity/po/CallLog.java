package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 通话记录（语音/视频通话结束后落库）
 */
public class CallLog implements Serializable {

    private Long id;

    /**
     * 发起方 userId
     */
    private String callerId;

    /**
     * 1=单聊 2=群呼
     */
    private Integer callType;

    /**
     * 单聊对方 userId
     */
    private String peerId;

    /**
     * 群呼群组 ID
     */
    private String groupId;

    /**
     * 1=音频 2=音视频
     */
    private Integer mediaType;

    /**
     * 通话开始时间(ms)
     */
    private Long startTime;

    /**
     * 通话结束时间(ms)
     */
    private Long endTime;

    /**
     * 1已接 2未接 3拒接 4取消 5忙线
     */
    private Integer status;

    /**
     * 参与人数
     */
    private Integer participantCount;

    /**
     * 记录创建时间(ms)
     */
    private Long createTime;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public String getCallerId() {
        return this.callerId;
    }

    public void setCallType(Integer callType) {
        this.callType = callType;
    }

    public Integer getCallType() {
        return this.callType;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getPeerId() {
        return this.peerId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupId() {
        return this.groupId;
    }

    public void setMediaType(Integer mediaType) {
        this.mediaType = mediaType;
    }

    public Integer getMediaType() {
        return this.mediaType;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getStartTime() {
        return this.startTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public Long getEndTime() {
        return this.endTime;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setParticipantCount(Integer participantCount) {
        this.participantCount = participantCount;
    }

    public Integer getParticipantCount() {
        return this.participantCount;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }
}
