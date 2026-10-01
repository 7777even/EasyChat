package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 表情包
 */
public class Emoji implements Serializable {

    /**
     * ID
     */
    private Long id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 存储路径
     */
    private String filePath;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 0=系统 1=自定义
     */
    private Integer emojiType;

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getEmojiType() {
        return emojiType;
    }

    public void setEmojiType(Integer emojiType) {
        this.emojiType = emojiType;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
