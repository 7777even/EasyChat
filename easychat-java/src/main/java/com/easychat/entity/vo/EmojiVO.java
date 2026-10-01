package com.easychat.entity.vo;

/**
 * 表情包出参
 */
public class EmojiVO {

    /**
     * ID
     */
    private Long id;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
}
