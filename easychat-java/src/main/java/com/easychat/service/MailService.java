package com.easychat.service;

/**
 * 邮件发送服务。
 *
 * <p><b>存在的理由</b>：邮箱验证码原先没有真实投递渠道，{@code UserInfoServiceImpl#sendEmailCode}
 * 只能把验证码写进应用日志。应用日志（容器 stdout、日志聚合平台、CI 构建归档）的读权限
 * 通常远宽于普通用户——「谁能看到日志谁就能重置任意账号（含 admin）的密码」。
 * 同时前端已向用户暴露完整找回密码流程，验证码只在日志里意味着该功能对真实用户永远不可用。
 *
 * <p><b>fail-closed 纪律（ADR-002）</b>：未配置邮件服务时<b>拒绝发送并抛业务异常</b>，
 * 绝不退回「打日志」这种降级。本接口所有实现都必须遵守此纪律。
 *
 * @since 2026-10-03 密码变更后会话失效（openspec/specs/password-bcrypt）
 */
public interface MailService {

    /**
     * 投递邮箱验证码。
     *
     * @param email 收件邮箱；非法格式 → {@code CODE_1001}
     * @param code  验证码；必须是纯数字（杜绝注入邮件头 / 模板）→ 非纯数字 {@code CODE_1001}
     * @param type  用途：0=注册，1=找回密码（其余按 1 处理）
     * @throws com.easychat.exception.BusinessException 入参非法 → {@code CODE_1001}；
     *                                            邮件服务未配置或投递失败 → {@code CODE_1002}
     */
    void sendVerifyCode(String email, String code, Integer type);
}