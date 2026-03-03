package com.easychat.entity.query;

/**
 * 朋友圈通知查询
 */
public class MomentNotifyQuery extends BaseParam {

    private Long id;

    private String userId;

    private Integer type;

    private Integer readStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(Integer readStatus) {
        this.readStatus = readStatus;
    }
}

