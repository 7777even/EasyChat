package com.easychat.entity.dto;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import com.easychat.entity.constants.Constants;

/**
 * 忘记密码（邮箱验证码重置）DTO。
 *
 * <p>2026-10-12 新增（openspec `2026-10-12-account-dto-validation`）：
 * 原先 {@code AccountController#resetPassword} 用「三个散参数 + 方法参数上的
 * {@code @NotEmpty} / {@code @Pattern}」手写校验，与 login / register 的
 * 「DTO + {@code @Valid}」是两套机制；而后者因缺 {@code @Valid} 完全未生效。
 * 本 DTO 把三者收进同一个对象，口径与 login / register 统一。
 *
 * <p>⚠ 字段名即**请求参数名**（Spring 按名绑定），改动会破坏前端契约。
 * 现前端 {@code UserPassword.vue} 提交 {@code email} / {@code code} / {@code newPassword}。
 */
public class UserResetPasswordDTO {

    /** 用户邮箱（作为账号） */
    @NotEmpty(message = "邮箱不能为空")
    private String email;

    /** 邮箱验证码 */
    @NotEmpty(message = "验证码不能为空")
    private String code;

    /**
     * 新密码。
     *
     * <p>规则复用 {@link Constants#REGEX_PASSWORD}（原手写校验即用同一常量，
     * 刻意**不趁机调整规则** —— 密码强度是独立的产品决策，不应夹带在「接线」改动里）。
     */
    @NotEmpty(message = "新密码不能为空")
    @Pattern(regexp = Constants.REGEX_PASSWORD, message = "密码格式不正确")
    private String newPassword;

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

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}