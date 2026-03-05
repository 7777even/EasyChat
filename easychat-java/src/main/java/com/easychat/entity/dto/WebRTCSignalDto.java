package com.easychat.entity.dto;

import java.io.Serializable;

/**
 * WebRTC 信令消息
 */
public class WebRTCSignalDto implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // 信令类型: offer, answer, ice-candidate, call, accept, reject, cancel, hangup
    private String signalType;
    
    // 通话ID
    private String callId;
    
    // 发送者ID
    private String fromUserId;
    
    // 接收者ID（单聊）或群组ID（群聊）
    private String toId;
    
    // 通话类型: 0-语音 1-视频
    private Integer callType;
    
    // 是否群组通话
    private Boolean isGroup;
    
    // SDP 或 ICE Candidate 数据
    private Object data;
    
    // 发送者昵称
    private String fromUserNickName;
    
    // 群组成员列表（群聊时使用）
    private String[] members;

    public String getSignalType() {
        return signalType;
    }

    public void setSignalType(String signalType) {
        this.signalType = signalType;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(String fromUserId) {
        this.fromUserId = fromUserId;
    }

    public String getToId() {
        return toId;
    }

    public void setToId(String toId) {
        this.toId = toId;
    }

    public Integer getCallType() {
        return callType;
    }

    public void setCallType(Integer callType) {
        this.callType = callType;
    }

    public Boolean getIsGroup() {
        return isGroup;
    }

    public void setIsGroup(Boolean isGroup) {
        this.isGroup = isGroup;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getFromUserNickName() {
        return fromUserNickName;
    }

    public void setFromUserNickName(String fromUserNickName) {
        this.fromUserNickName = fromUserNickName;
    }

    public String[] getMembers() {
        return members;
    }

    public void setMembers(String[] members) {
        this.members = members;
    }
}
