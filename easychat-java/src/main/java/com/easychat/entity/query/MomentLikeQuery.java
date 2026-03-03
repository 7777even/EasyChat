package com.easychat.entity.query;

/**
 * 朋友圈点赞查询
 */
public class MomentLikeQuery extends BaseParam {

    private Long id;

    private Long momentId;

    private String userId;

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
}

