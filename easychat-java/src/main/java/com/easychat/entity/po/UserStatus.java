package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 用户状态
 */
public class UserStatus implements Serializable {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 状态文字内容
     */
    private String content;

    /**
     * 状态图片URL
     */
    private String imageUrl;

    /**
     * 创建时间戳
     */
    private Long createTime;

    /**
     * 过期时间戳
     */
    private Long expireTime;

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

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Long expireTime) {
        this.expireTime = expireTime;
    }
}
