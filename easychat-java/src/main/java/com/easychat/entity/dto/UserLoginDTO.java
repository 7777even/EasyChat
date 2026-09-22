package com.easychat.entity.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotEmpty;

/**
 * 用户登录 DTO
 */
public class UserLoginDTO {

    /** 验证码 key */
    @NotEmpty(message = "验证码Key不能为空")
    private String checkCodeKey;

    /** 用户邮箱 */
    @NotEmpty(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 用户密码 */
    @NotEmpty(message = "密码不能为空")
    private String password;

    /** 验证码 */
    @NotEmpty(message = "验证码不能为空")
    private String checkCode;

    public String getCheckCodeKey() {
        return checkCodeKey;
    }

    public void setCheckCodeKey(String checkCodeKey) {
        this.checkCodeKey = checkCodeKey;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCheckCode() {
        return checkCode;
    }

    public void setCheckCode(String checkCode) {
        this.checkCode = checkCode;
    }
}
