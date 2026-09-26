package com.easychat.entity.vo;

import java.io.Serializable;

/**
 * 敏感词库管理列表出参
 */
public class SensitiveWordVO implements Serializable {

    /**
     * 词条ID
     */
    private Long id;

    /**
     * 敏感词
     */
    private String word;

    /**
     * 级别 1提醒 2替换 3禁止发送
     */
    private Integer level;

    /**
     * 状态 1启用 0停用
     */
    private Integer status;

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

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
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
}
