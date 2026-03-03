package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 朋友圈动态
 */
public class Moment implements Serializable {

    private Long id;

    private String userId;

    private String content;

    private Integer mediaType;

    private String location;

    /**
     * 0公开 1好友 2私密 3白名单 4黑名单过滤
     */
    private Integer visibility;

    /**
     * 白名单/分组列表 JSON
     */
    private String visibleList;

    /**
     * 黑名单过滤列表 JSON
     */
    private String invisibleList;

    /**
     * 1正常 0删除
     */
    private Integer status;

    private Long createTime;

    private Long updateTime;

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getMediaType() {
        return mediaType;
    }

    public void setMediaType(Integer mediaType) {
        this.mediaType = mediaType;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getVisibility() {
        return visibility;
    }

    public void setVisibility(Integer visibility) {
        this.visibility = visibility;
    }

    public String getVisibleList() {
        return visibleList;
    }

    public void setVisibleList(String visibleList) {
        this.visibleList = visibleList;
    }

    public String getInvisibleList() {
        return invisibleList;
    }

    public void setInvisibleList(String invisibleList) {
        this.invisibleList = invisibleList;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}

