package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 朋友圈评论视图
 */
public class MomentCommentVO implements Serializable {
    private Long id;
    private Long momentId;
    private String userId;
    private String nickName;
    private Long parentId;
    private String replyToUserId;
    private String replyToNickName;
    private String content;
    private Long createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMomentId() {
        return momentId;
    }

    public void setMomentId(Long momentId) {
        this.momentId = momentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getReplyToUserId() {
        return replyToUserId;
    }

    public void setReplyToUserId(String replyToUserId) {
        this.replyToUserId = replyToUserId;
    }

    public String getReplyToNickName() {
        return replyToNickName;
    }

    public void setReplyToNickName(String replyToNickName) {
        this.replyToNickName = replyToNickName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}


