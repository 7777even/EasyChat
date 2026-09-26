package com.easychat.entity.query;

/**
 * 通话记录查询参数
 */
public class CallLogQuery extends BaseParam {

    /**
     * ID
     */
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
     * 1已接 2未接 3拒接 4取消 5忙线
     */
    private Integer status;

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

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }
}
