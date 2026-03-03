package com.easychat.entity.query;

/**
 * 朋友圈动态查询
 */
public class MomentQuery extends BaseParam {

    private Long id;

    private String userId;

    private Integer visibility;

    private Integer status;

    private Long startCreateTime;

    private Long endCreateTime;

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

    public Integer getVisibility() {
        return visibility;
    }

    public void setVisibility(Integer visibility) {
        this.visibility = visibility;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getStartCreateTime() {
        return startCreateTime;
    }

    public void setStartCreateTime(Long startCreateTime) {
        this.startCreateTime = startCreateTime;
    }

    public Long getEndCreateTime() {
        return endCreateTime;
    }

    public void setEndCreateTime(Long endCreateTime) {
        this.endCreateTime = endCreateTime;
    }
}

