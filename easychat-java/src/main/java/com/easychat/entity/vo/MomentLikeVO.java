package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 朋友圈点赞视图
 */
public class MomentLikeVO implements Serializable {
    private String userId;
    private String nickName;
    private Long createTime;

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

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}


