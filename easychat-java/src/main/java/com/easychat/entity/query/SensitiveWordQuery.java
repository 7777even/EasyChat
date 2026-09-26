package com.easychat.entity.query;

/**
 * 敏感词库管理列表查询（分页 + 过滤，仅存活词条）
 */
public class SensitiveWordQuery extends BaseParam {

    /**
     * 关键词（模糊匹配词条，null=不限制）
     */
    private String keyword;

    /**
     * 级别 1提醒 2替换 3禁止发送（null=全部）
     */
    private Integer level;

    /**
     * 状态 1启用 0停用（null=全部）
     */
    private Integer status;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
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
}
