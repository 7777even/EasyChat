package com.easychat.entity.vo;

/**
 * 收藏出参
 */
public class FavoriteVO {

    /**
     * ID
     */
    private Long id;

    /**
     * 消息ID
     */
    private Long messageId;

    /**
     * 收藏内容
     */
    private String content;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 创建时间毫秒
     */
    private Long createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
