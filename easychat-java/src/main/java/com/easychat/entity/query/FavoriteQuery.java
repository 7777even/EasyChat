package com.easychat.entity.query;

/**
 * 收藏查询条件
 */
public class FavoriteQuery extends BaseParam {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 消息ID
     */
    private Long messageId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }
}
