package com.easychat.entity.po;

import java.io.Serializable;

/**
 * 邮箱验证码（注册校验 / 忘记密码找回）
 */
public class EmailVerifyCode implements Serializable {

    private Long id;

    /** 邮箱 */
    private String email;

    /** 验证码 */
    private String code;

    /** 0注册 1找回密码 2修改邮箱 */
    private Integer type;

    /** 0未使用 1已使用 */
    private Integer status;

    /** 过期时间戳毫秒 */
    private Long expireTime;

    /** 创建时间毫秒 */
    private Long createTime;

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

    public Long getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Long expireTime) {
        this.expireTime = expireTime;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
