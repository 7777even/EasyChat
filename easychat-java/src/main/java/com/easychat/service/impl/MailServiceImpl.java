package com.easychat.service.impl;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.exception.BusinessException;
import com.easychat.service.MailService;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link MailService} 的 SMTP 实现。
 *
 * <p><b>为什么用「配置 Map + 手工判断」而不是直接 {@code @Value} 注入 host</b>：
 * fail-closed 要求「host 为空就拒绝发送」，而 {@code @Value("${spring.mail.host:}")} 会把
 * 缺失静默变成空串——两者行为其实等价，但把 host/port/username/password/from 收在一个
 * {@code mailProps} 里，便于单测整体置空/置满（见 {@code MailServiceTest}），也便于一眼看清
 * 「这套能力依赖哪几个外部配置」。
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>邮件主题是<b>固定文案</b>，绝不拼接用户输入的 email —— 邮件头注入面；</li>
 *   <li>验证码强制纯数字（{@link #CODE_PATTERN}）—— 同时挡住邮件头注入与模板注入；</li>
 *   <li>任何失败路径的异常消息都<b>不含验证码</b>：异常会经全局异常处理器回给前端并进日志。</li>
 * </ul>
 *
 * @since 2026-10-03 密码变更后会话失效（openspec/specs/password-bcrypt）
 */
@Service
public class MailServiceImpl implements MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailServiceImpl.class);

    /**
     * 验证码必须是纯数字。既挡邮件头注入（{@code \r\n Bcc: ...}），也挡模板注入。
     */
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{4,8}$");

    /**
     * 邮箱格式的宽松校验。刻意不用严格 RFC 5322 规则——真实的用户邮箱千奇百怪，
     * 过严会把合法地址拒掉；真正的送达校验交给 SMTP。
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 主题固定文案，不含任何用户输入 */
    private static final String SUBJECT = "EasyChat 验证码";

    /** 验证码有效期文案，与 UserInfoServiceImpl 中写入的 10 分钟保持一致 */
    private static final String VALIDITY_TEXT = "10 分钟内有效";

    @Autowired(required = false)
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.port:}")
    private String mailPort;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${spring.mail.from:}")
    private String mailFrom;

    /**
     * 供单测整体替换的配置视图（见 {@code MailServiceTest#givenMailConfigured}）。
     * 生产环境不使用——生产走上面的 {@code @Value} 注入。
     */
    private Map<String, Object> mailProps;

    @Override
    public void sendVerifyCode(String email, String code, Integer type) {
        // ── 1. 入参守卫（先于任何配置判断：入参错就是入参错，不该报「服务未配置」）──
        if (StringTools.isEmpty(email) || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "邮箱格式不正确");
        }
        if (StringTools.isEmpty(code) || !CODE_PATTERN.matcher(code.trim()).matches()) {
            throw new BusinessException(ResponseCodeEnum.CODE_1001, "验证码格式不正确");
        }
        String safeEmail = email.trim();
        String safeCode = code.trim();

        // ── 2. fail-closed：未配置邮件服务则拒绝，绝不退回打日志 ──
        Map<String, Object> props = resolveProps();
        Object host = props.get("host");
        if (StringTools.isEmpty(host == null ? null : String.valueOf(host))) {
            // 消息里不带验证码：异常会回给前端并进日志
            throw new BusinessException(ResponseCodeEnum.CODE_1002, "邮件服务未配置，无法发送验证码");
        }

        if (javaMailSender == null) {
            // 有配置但没有 sender bean（依赖缺失 / 自动装配未生效），同样 fail-closed
            throw new BusinessException(ResponseCodeEnum.CODE_1002, "邮件发送器不可用，无法发送验证码");
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(resolveFrom(props));
            helper.setTo(safeEmail);
            helper.setSubject(SUBJECT);
            helper.setText(buildBody(safeCode, type == null ? 0 : type), false);
            javaMailSender.send(message);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 兜底：不把原始异常消息透出去（可能含 SMTP 账号 / 回显内容），更不能含验证码
            logger.warn("邮件投递失败 type={}：{}", type == null ? 0 : type, e.getMessage());
            throw new BusinessException(ResponseCodeEnum.CODE_1002, "验证码邮件发送失败，请稍后重试");
        }
    }

    /**
     * 取配置视图：优先单测注入的 {@link #mailProps}，否则由 {@code @Value} 字段组装。
     */
    private Map<String, Object> resolveProps() {
        if (mailProps != null) {
            return mailProps;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("host", mailHost);
        props.put("port", mailPort);
        props.put("username", mailUsername);
        props.put("password", mailPassword);
        props.put("from", mailFrom);
        return props;
    }

    /**
     * 发件人：优先 {@code spring.mail.from}，回退 SMTP 登录账号。
     */
    private String resolveFrom(Map<String, Object> props) {
        Object from = props.get("from");
        String fromValue = from == null ? null : String.valueOf(from);
        if (!StringTools.isEmpty(fromValue)) {
            return fromValue;
        }
        Object username = props.get("username");
        return username == null ? null : String.valueOf(username);
    }

    /**
     * 组装正文。验证码出现在正文是<b>预期行为</b>——它就是要给用户看的。
     * 主题则相反，必须是固定文案。
     */
    private String buildBody(String code, int type) {
        String purpose = type == 1 ? "找回密码" : "注册账号";
        return "您正在进行「" + purpose + "」操作，验证码为：" + code + "\n"
                + VALIDITY_TEXT + "。\n"
                + "若非本人操作，请忽略本邮件，不要将验证码告知他人。\n";
    }
}