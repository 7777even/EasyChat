package com.easychat.entity.query;


/**
 * 邮箱验证码 查询参数
 */
public class EmailVerifyCodeQuery extends BaseParam {

    private Long id;

    private String email;

    private String code;

    private Integer type;

    private Integer status;

    /** 只查未过期的（expire_time > now） */
    private Long currentTime;

    // 2026-10-06：原本类自带 private String orderBy + getter/setter（与 BaseParam 重复），
    //   现已移除 —— 排序统一走 BaseParam 的 sortField / sortDirection / sortOption 白名单三件套。
    //   留着它等于在本类上留一个「写任意串进 SQL」的后门。

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(Long currentTime) {
        this.currentTime = currentTime;
    }
}
