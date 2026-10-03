package com.easychat.service;

import com.easychat.exception.BusinessException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import javax.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link com.easychat.service.impl.MailServiceImpl} 单元测试。
 *
 * <p><b>为什么这些必须有单测</b>：本类承担 ADR-002「fail-closed」纪律——
 * 邮件服务未配置时必须<b>拒绝发送</b>，而改造前的行为是把验证码明文写进应用日志。
 * 日志读权限（容器 stdout / 日志聚合 / CI 归档）通常远宽于普通用户，
 * 等价于「谁能看到日志谁就能重置任意账号（含 admin）的密码」。
 * 这条纪律一旦被后人「顺手优化」回退，<b>不会有任何异常、任何告警</b>，
 * 所以必须由单测 + 门禁双重锁住。
 *
 * <p><b>手法</b>：不用 {@code @SpringBootTest}，直接 new 出实现类并用
 * {@link ReflectionTestUtils} 注入 mock 的 {@link JavaMailSender} 与配置 Map，
 * 让三条关键路径各自独立可断言。
 *
 * @since 2026-10-03 密码变更后会话失效（openspec/specs/password-bcrypt）
 */
@RunWith(MockitoJUnitRunner.class)
public class MailServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String CODE = "123456";

    private MailService mailService;

    @Mock
    private JavaMailSender mailSender;

    @Before
    public void setUp() {
        mailService = new com.easychat.service.impl.MailServiceImpl();
        ReflectionTestUtils.setField(mailService, "javaMailSender", mailSender);
    }

    /** 配置为「邮件服务已就绪」 */
    private void givenMailConfigured() {
        Map<String, Object> props = new HashMap<>();
        props.put("host", "smtp.example.com");
        props.put("port", 465);
        props.put("username", "noreply@example.com");
        props.put("password", "secret");
        props.put("from", "noreply@example.com");
        ReflectionTestUtils.setField(mailService, "mailProps", props);
    }

    /** 配置为「邮件服务未配置」 */
    private void givenMailNotConfigured() {
        ReflectionTestUtils.setField(mailService, "mailProps", new HashMap<String, Object>());
    }

    private BusinessException catchBusiness(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e;
        }
        fail("期望抛 BusinessException，但没有抛出");
        return null;
    }

    // ==================== fail-closed（核心纪律） ====================

    @Test
    public void send_verify_mailNotConfigured_throws1002() {
        givenMailNotConfigured();

        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, CODE, 1));

        assertEquals("未配 SMTP 应报 CODE_1002", Integer.valueOf(1002), e.getCode());
    }

    @Test
    public void send_verify_mailNotConfigured_neverTouchesSender() {
        givenMailNotConfigured();

        try {
            mailService.sendVerifyCode(EMAIL, CODE, 1);
        } catch (BusinessException expected) {
            // 断言不变量
        }

        // 未配置时连 sender 都不许碰——否则一旦有人加了 fallback 就会漏发
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    public void send_verify_mailNotConfigured_messageDoesNotLeakCode() {
        givenMailNotConfigured();

        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, CODE, 1));

        // 异常消息里绝不能带验证码，否则它会经全局异常处理器回给前端 / 进日志
        assertTrue("异常消息泄露了验证码：" + e.getMessage(),
                e.getMessage() == null || !e.getMessage().contains(CODE));
    }

    @Test
    public void send_verify_mailNotConfigured_messageDoesNotLeakCode_onNullConfig() {
        // mailProps 整个为 null（配置未加载）也不能 NPE，更不能把码带出去
        ReflectionTestUtils.setField(mailService, "mailProps", null);

        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, CODE, 1));

        assertEquals(Integer.valueOf(1002), e.getCode());
        assertTrue(e.getMessage() == null || !e.getMessage().contains(CODE));
    }

    // ==================== 投递失败包装 ====================

    @Test
    public void send_verify_senderThrows_wrapsAs1002() {
        givenMailConfigured();
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((javax.mail.Session) null));
        
        org.mockito.Mockito.doThrow(new MailSendException("SMTP 认证失败"))
                .when(mailSender).send(any(MimeMessage.class));

        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, CODE, 1));

        assertEquals(Integer.valueOf(1002), e.getCode());
    }

    @Test
    public void send_verify_senderThrows_messageDoesNotLeakCode() {
        givenMailConfigured();
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((javax.mail.Session) null));
        
        org.mockito.Mockito.doThrow(new MailSendException("SMTP 认证失败"))
                .when(mailSender).send(any(MimeMessage.class));

        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, CODE, 1));

        assertTrue("异常消息泄露了验证码：" + e.getMessage(),
                e.getMessage() == null || !e.getMessage().contains(CODE));
    }

    // ==================== 正常路径 ====================

    @Test
    public void send_verify_configured_sendsOnce() throws Exception {
        givenMailConfigured();
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((javax.mail.Session) null));
        

        mailService.sendVerifyCode(EMAIL, CODE, 1);

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    public void send_verify_configured_subjectIsFixedLiteral() throws Exception {
        // 防邮件头注入：主题不得拼接用户输入的 email
        givenMailConfigured();
        MimeMessage message = new MimeMessage((javax.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        

        mailService.sendVerifyCode(EMAIL, CODE, 1);

        String subject = message.getSubject();
        assertTrue("主题为空", subject != null && !subject.trim().isEmpty());
        assertTrue("主题泄露了用户邮箱：" + subject, !subject.contains(EMAIL));
    }

    @Test
    public void send_verify_configured_recipientIsTheGivenEmail() throws Exception {
        givenMailConfigured();
        MimeMessage message = new MimeMessage((javax.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        

        mailService.sendVerifyCode(EMAIL, CODE, 1);

        assertTrue("收件人不是传入的邮箱",
                message.getAllRecipients() != null && message.getAllRecipients().length == 1
                        && EMAIL.equals(message.getAllRecipients()[0].toString()));
    }

    // ==================== 入参守卫 ====================

    @Test
    public void send_verify_blankEmail_throws1001() {
        givenMailConfigured();
        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode("  ", CODE, 1));
        assertEquals(Integer.valueOf(1001), e.getCode());
    }

    @Test
    public void send_verify_nullCode_throws1001() {
        givenMailConfigured();
        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, null, 1));
        assertEquals(Integer.valueOf(1001), e.getCode());
    }

    @Test
    public void send_verify_nonNumericCode_throws1001() {
        // 验证码必须是纯数字，杜绝注入邮件头 / 模板
        givenMailConfigured();
        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode(EMAIL, "12\r\nBcc: victim@x.com", 1));
        assertEquals(Integer.valueOf(1001), e.getCode());
    }

    @Test
    public void send_verify_invalidEmail_throws1001() {
        givenMailConfigured();
        BusinessException e = catchBusiness(() -> mailService.sendVerifyCode("not-an-email", CODE, 1));
        assertEquals(Integer.valueOf(1001), e.getCode());
    }
}