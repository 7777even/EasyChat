package com.easychat.entity.query;

/**
 * 表情包查询条件
 */
public class EmojiQuery extends BaseParam {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 表情包类型
     */
    private Integer emojiType;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getEmojiType() {
        return emojiType;
    }

    public void setEmojiType(Integer emojiType) {
        this.emojiType = emojiType;
    }
}
