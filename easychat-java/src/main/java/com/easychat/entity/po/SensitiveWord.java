package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 敏感词
 */
public class SensitiveWord implements Serializable {

    /**
     * ID
     */
    private Long id;

    /**
     * 敏感词
     */
    private String word;

    /**
     * 1提醒 2替换 3禁止发送
     */
    private Integer level;

    /**
     * 1启用 0停用
     */
    private Integer status;

    /**
     * 逻辑删除标记：0=存活，非0=删除时间戳毫秒
     */
    private Long deleteFlag;

    /**
     * 创建时间毫秒
     */
    private Long createTime;

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return this.id;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getWord() {
        return this.word;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getLevel() {
        return this.level;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setDeleteFlag(Long deleteFlag) {
        this.deleteFlag = deleteFlag;
    }

    public Long getDeleteFlag() {
        return this.deleteFlag;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getCreateTime() {
        return this.createTime;
    }
}
