package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 朋友圈点赞
 */
public class MomentLike implements Serializable {

    private Long id;

    private Long momentId;

    private String userId;

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

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}

