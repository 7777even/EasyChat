package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.BeautyAccountStatusEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserStatusEnum;
import com.easychat.entity.po.EmailVerifyCode;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.po.UserInfoBeauty;
import com.easychat.entity.query.EmailVerifyCodeQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.UserInfoVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.EmailVerifyCodeMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.mappers.UserInfoBeautyMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.ChatSessionUserService;
import com.easychat.service.MailService;
import com.easychat.service.OperationLogService;
import com.easychat.service.UserContactService;
import com.easychat.websocket.MessageHandler;
import com.easychat.config.EasyChatProperties;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserInfoServiceImpl 单元测试
 */
@RunWith(MockitoJUnitRunner.class)
public class UserInfoServiceImplTest {

    @InjectMocks
    private UserInfoServiceImpl userInfoService;

    @Mock
    private UserInfoMapper<UserInfo, com.easychat.entity.query.UserInfoQuery> userInfoMapper;

    @Mock
    private AppConfig appConfig;

    @Mock
    private RedisComponet redisComponet;

    /** 2026-10-03 新增：验证码投递通道（取代原先的「写日志」降级实现） */
    @Mock
    private MailService mailService;

    @Mock
    private ChatSessionUserService chatSessionUserService;

    @Mock
    private MessageHandler messageHandler;

    @Mock
    private UserContactService userContactService;

    @Mock
    private UserInfoBeautyMapper<UserInfoBeauty, com.easychat.entity.query.UserInfoBeautyQuery> userInfoBeautyMapper;

    @Mock
    private EmailVerifyCodeMapper<EmailVerifyCode, EmailVerifyCodeQuery> emailVerifyCodeMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private EasyChatProperties easyChatProperties;

    @Mock
    private EasyChatProperties.Login login;

    @Mock
    private OperationLogService operationLogService;

    @Before
    public void setUp() {
        // 默认不限制单端登录
        when(easyChatProperties.getLogin()).thenReturn(login);
        when(login.getSingleDevice()).thenReturn(false);
    }

    // ======================== 注册 ========================

    @Test
    public void register_success() {
        String email = "test@example.com";
        String nickName = "测试用户";
        String password = "password123";

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(userInfoBeautyMapper.selectByEmail(email)).thenReturn(null);
        when(userInfoMapper.insert(any(UserInfo.class))).thenReturn(1);

        userInfoService.register(email, nickName, password);

        verify(userInfoMapper).insert(any(UserInfo.class));
        verify(userContactService).addContact4Robot(anyString());
    }

    @Test(expected = BusinessException.class)
    public void register_emailAlreadyExists() {
        String email = "test@example.com";
        UserInfo existingUser = new UserInfo();
        existingUser.setEmail(email);

        when(userInfoMapper.selectByEmail(email)).thenReturn(existingUser);

        userInfoService.register(email, "测试用户", "password123");
    }

    @Test
    public void register_withBeautyAccount() {
        String email = "beauty@example.com";
        String nickName = "靓号用户";
        String password = "password123";

        UserInfoBeauty beauty = new UserInfoBeauty();
        beauty.setUserId("123456");
        beauty.setStatus(BeautyAccountStatusEnum.NO_USE.getStatus());

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(userInfoBeautyMapper.selectByEmail(email)).thenReturn(beauty);
        when(userInfoMapper.insert(any(UserInfo.class))).thenReturn(1);
        when(userInfoBeautyMapper.updateById(any(UserInfoBeauty.class), any())).thenReturn(1);

        userInfoService.register(email, nickName, password);

        verify(userInfoMapper).insert(any(UserInfo.class));
        verify(userInfoBeautyMapper).updateById(any(UserInfoBeauty.class), any());
    }

    // ======================== 登录 ========================

    @Test
    public void login_success() {
        String email = "test@example.com";
        String password = "password123";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678901");
        userInfo.setEmail(email);
        userInfo.setNickName("测试用户");
        // 使用 MD5 加密密码（模拟旧用户）
        userInfo.setPassword(com.easychat.utils.StringTools.encodeByMD5(password));
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
        userInfo.setCreateTime(new Date());

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);
        when(userContactMapper.selectList(any(UserContactQuery.class))).thenReturn(new ArrayList<>());
        when(userInfoMapper.updateByUserId(any(UserInfo.class), anyString())).thenReturn(1);
        // saveTokenUserInfoDto returns void, no stub needed

        UserInfoVO result = userInfoService.login(email, password);

        assertNotNull(result);
        assertEquals("U12345678901", result.getUserId());
        assertNotNull(result.getToken());
    }

    @Test(expected = BusinessException.class)
    public void login_wrongPassword() {
        String email = "test@example.com";
        String wrongPassword = "wrongpassword";

        UserInfo userInfo = new UserInfo();
        userInfo.setEmail(email);
        userInfo.setPassword("correctpassword");
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);

        userInfoService.login(email, wrongPassword);
    }

    @Test(expected = BusinessException.class)
    public void login_userDisabled() {
        String email = "test@example.com";
        String password = "password123";

        UserInfo userInfo = new UserInfo();
        userInfo.setEmail(email);
        userInfo.setPassword(password);
        userInfo.setStatus(UserStatusEnum.DISABLE.getStatus());

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);

        userInfoService.login(email, password);
    }

    @Test(expected = BusinessException.class)
    public void login_userNotFound() {
        String email = "notexist@example.com";

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);

        userInfoService.login(email, "password123");
    }

    // ==================== 密码传递口径（BCrypt 回归，2026-10-01） ====================
    // 背景：BCrypt 改造后前端登录仍发 md5(明文)、注册发明文，口径分裂导致登录必失败。
    // 以下用例把「客户端发明文、服务端 BCrypt 校验」这一契约固化，防复发。

    /** BCrypt 账号：明文登录成功 */
    @Test
    public void login_bcryptStoredPassword_withPlaintext_success() {
        String email = "bcrypt@example.com";
        String password = "password123";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678902");
        userInfo.setEmail(email);
        userInfo.setNickName("BCrypt用户");
        userInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(password));
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
        userInfo.setCreateTime(new Date());

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);
        when(userContactMapper.selectList(any(UserContactQuery.class))).thenReturn(new ArrayList<>());

        UserInfoVO result = userInfoService.login(email, password);

        assertNotNull(result);
        assertEquals("U12345678902", result.getUserId());
    }

    /** 注册入库的是 BCrypt 哈希，且该哈希可用同一明文通过登录校验 */
    @Test
    public void register_storesBcryptAndPlaintextCanLogin() {
        String email = "newbcrypt@example.com";
        String password = "password123";

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(userInfoBeautyMapper.selectByEmail(email)).thenReturn(null);
        when(userInfoMapper.insert(any(UserInfo.class))).thenReturn(1);

        userInfoService.register(email, "新用户", password);

        ArgumentCaptor<UserInfo> captor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoMapper).insert(captor.capture());
        String stored = captor.getValue().getPassword();

        assertTrue("注册应写入 BCrypt 哈希", com.easychat.utils.PasswordEncoder.isBCrypt(stored));
        assertEquals("BCrypt 哈希长度应为 60", 60, stored.length());
        assertTrue("同一明文应能通过校验", com.easychat.utils.PasswordEncoder.matches(password, stored));
    }

    /** 存量 MD5 账号：明文登录成功后自动升级为 BCrypt */
    @Test
    public void login_md5Password_autoUpgradedToBcrypt() {
        String email = "legacy@example.com";
        String password = "password123";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678903");
        userInfo.setEmail(email);
        userInfo.setNickName("老用户");
        userInfo.setPassword(com.easychat.utils.StringTools.encodeByMD5(password));
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
        userInfo.setCreateTime(new Date());

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);
        when(userContactMapper.selectList(any(UserContactQuery.class))).thenReturn(new ArrayList<>());
        when(userInfoMapper.updateByUserId(any(UserInfo.class), eq("U12345678903"))).thenReturn(1);

        UserInfoVO result = userInfoService.login(email, password);

        assertNotNull(result);
        ArgumentCaptor<UserInfo> captor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoMapper).updateByUserId(captor.capture(), eq("U12345678903"));
        String upgraded = captor.getValue().getPassword();
        assertTrue("老账号密码应升级为 BCrypt", com.easychat.utils.PasswordEncoder.isBCrypt(upgraded));
        assertTrue("升级后同一明文仍可校验", com.easychat.utils.PasswordEncoder.matches(password, upgraded));
    }

    /** 已升级为 BCrypt 的账号：客户端若仍发 md5 摘要，登录必须失败且不写库 */
    @Test(expected = BusinessException.class)
    public void login_bcryptStored_rejectsMd5Digest() {
        String email = "bcrypt@example.com";
        String password = "password123";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678902");
        userInfo.setEmail(email);
        userInfo.setPassword(com.easychat.utils.PasswordEncoder.encode(password));
        userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);

        try {
            // 模拟旧版客户端行为：把明文先做一次 MD5 再提交
            userInfoService.login(email, com.easychat.utils.StringTools.encodeByMD5(password));
        } finally {
            verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
        }
    }

    // ======================== 修改密码 ========================

    @Test
    public void updatePassword_success() {
        String userId = "U12345678901";
        String oldPassword = "oldpassword";
        String newPassword = "newpassword";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        // 密码是 MD5 加密存储的
        userInfo.setPassword(com.easychat.utils.StringTools.encodeByMD5(oldPassword));

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);
        when(userInfoMapper.updateByUserId(any(UserInfo.class), eq(userId))).thenReturn(1);

        userInfoService.updatePassword(userId, oldPassword, newPassword);

        verify(userInfoMapper).updateByUserId(any(UserInfo.class), eq(userId));
    }

    @Test(expected = BusinessException.class)
    public void updatePassword_wrongOldPassword() {
        String userId = "U12345678901";
        String wrongOldPassword = "wrongpassword";
        String newPassword = "newpassword";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setPassword("correctpassword");

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);

        userInfoService.updatePassword(userId, wrongOldPassword, newPassword);
    }

    @Test(expected = BusinessException.class)
    public void updatePassword_sameAsOld() {
        String userId = "U12345678901";
        String password = "password123";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setPassword(password);

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);

        userInfoService.updatePassword(userId, password, password);
    }

    @Test(expected = BusinessException.class)
    public void updatePassword_emptyParams() {
        userInfoService.updatePassword("U12345678901", "", "newpassword");
    }

    // ======================== 发送邮箱验证码 ========================

    @Test
    public void sendEmailCode_register_success() {
        String email = "new@example.com";
        Integer type = 0;

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class))).thenReturn(new ArrayList<>());
        when(emailVerifyCodeMapper.insert(any(EmailVerifyCode.class))).thenReturn(1);

        userInfoService.sendEmailCode(email, type);

        verify(emailVerifyCodeMapper).insert(any(EmailVerifyCode.class));
    }

    @Test
    public void sendEmailCode_deliversViaMailService() {
        // 2026-10-03：验证码必须经 MailService 真实投递，
        // 不再由 sendEmailCode 自己 logger.info 打出来（那是凭据泄露通道）。
        String email = "new@example.com";

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class))).thenReturn(new ArrayList<>());
        when(emailVerifyCodeMapper.insert(any(EmailVerifyCode.class))).thenReturn(1);

        userInfoService.sendEmailCode(email, 0);

        // 落库的码与发出去的码必须是同一个，否则用户收到邮件却输不进验证码
        ArgumentCaptor<EmailVerifyCode> codeCaptor = ArgumentCaptor.forClass(EmailVerifyCode.class);
        verify(emailVerifyCodeMapper).insert(codeCaptor.capture());
        ArgumentCaptor<String> mailCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerifyCode(eq(email), mailCodeCaptor.capture(), eq(0));
        assertEquals(codeCaptor.getValue().getCode(), mailCodeCaptor.getValue());
    }

    @Test
    public void sendEmailCode_mailServiceFailure_propagates() {
        // 邮件投递失败（未配 SMTP 等）必须向上抛，由全局异常处理器转 CODE_1002，
        // 而不是吞掉后假装成功——否则用户点了「发送验证码」却永远收不到。
        String email = "new@example.com";

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class))).thenReturn(new ArrayList<>());
        when(emailVerifyCodeMapper.insert(any(EmailVerifyCode.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new BusinessException(ResponseCodeEnum.CODE_1002, "邮件服务未配置，无法发送验证码"))
                .when(mailService).sendVerifyCode(anyString(), anyString(), anyInt());

        try {
            userInfoService.sendEmailCode(email, 0);
            fail("期望邮件投递失败向上抛出");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(1002), e.getCode());
        }
    }

    @Test(expected = BusinessException.class)
    public void sendEmailCode_register_emailExists() {
        String email = "existing@example.com";
        Integer type = 0;

        UserInfo existingUser = new UserInfo();
        existingUser.setEmail(email);

        when(userInfoMapper.selectByEmail(email)).thenReturn(existingUser);

        userInfoService.sendEmailCode(email, type);
    }

    @Test(expected = BusinessException.class)
    public void sendEmailCode_reset_userNotFound() {
        String email = "notexist@example.com";
        Integer type = 1;

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);

        userInfoService.sendEmailCode(email, type);
    }

    @Test(expected = BusinessException.class)
    public void sendEmailCode_tooFrequent() {
        String email = "test@example.com";
        Integer type = 0;

        UserInfo userInfo = new UserInfo();
        userInfo.setEmail(email);

        EmailVerifyCode recentCode = new EmailVerifyCode();
        recentCode.setCreateTime(System.currentTimeMillis() - 30 * 1000L); // 30秒前

        when(userInfoMapper.selectByEmail(email)).thenReturn(null);
        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class)))
                .thenReturn(Collections.singletonList(recentCode));

        userInfoService.sendEmailCode(email, type);
    }

    // ======================== 重置密码 ========================

    @Test
    public void resetPasswordByEmail_success() {
        String email = "test@example.com";
        String code = "123456";
        String newPassword = "newpassword";

        EmailVerifyCode verifyCode = new EmailVerifyCode();
        verifyCode.setEmail(email);
        verifyCode.setCode(code);
        verifyCode.setType(1);
        verifyCode.setStatus(0);
        verifyCode.setExpireTime(System.currentTimeMillis() + 10 * 60 * 1000L);

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678901");
        userInfo.setEmail(email);

        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class)))
                .thenReturn(Collections.singletonList(verifyCode));
        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);
        when(userInfoMapper.updateByUserId(any(UserInfo.class), anyString())).thenReturn(1);
        when(emailVerifyCodeMapper.updateByParam(any(EmailVerifyCode.class), any(EmailVerifyCodeQuery.class)))
                .thenReturn(1);

        userInfoService.resetPasswordByEmail(email, code, newPassword);

        verify(userInfoMapper).updateByUserId(any(UserInfo.class), eq("U12345678901"));
        verify(emailVerifyCodeMapper).updateByParam(any(EmailVerifyCode.class), any(EmailVerifyCodeQuery.class));
    }

    @Test(expected = BusinessException.class)
    public void resetPasswordByEmail_codeNotFound() {
        String email = "test@example.com";
        String code = "wrongcode";

        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class)))
                .thenReturn(new ArrayList<>());

        userInfoService.resetPasswordByEmail(email, code, "newpassword");
    }

    @Test(expected = BusinessException.class)
    public void resetPasswordByEmail_codeExpired() {
        String email = "test@example.com";
        String code = "123456";

        EmailVerifyCode verifyCode = new EmailVerifyCode();
        verifyCode.setEmail(email);
        verifyCode.setCode(code);
        verifyCode.setType(1);
        verifyCode.setStatus(0);
        verifyCode.setExpireTime(System.currentTimeMillis() - 1000L); // 已过期

        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class)))
                .thenReturn(Collections.singletonList(verifyCode));

        userInfoService.resetPasswordByEmail(email, code, "newpassword");
    }

    // ======================== 密码变更后会话失效（2026-10-03） ========================
    //
    // 背景：改密 / 找回密码此前只写库，既不清 Redis Token 也不推 FORCE_OFF_LINE，
    // 而 RedisComponet#cleanUserTokenByUserId 已实现却全仓零调用
    // → 账号被盗后受害者改密码，攻击者凭旧 Token（TTL 2 天）照常在线。
    // openspec/changes/2026-10-03-password-session-and-mail

    @Test
    public void updatePassword_success_invalidatesAllSessions() {
        String userId = "U12345678901";
        String oldPassword = "oldpassword";
        String newPassword = "newpassword";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setPassword(com.easychat.utils.StringTools.encodeByMD5(oldPassword));

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);
        when(userInfoMapper.updateByUserId(any(UserInfo.class), eq(userId))).thenReturn(1);

        userInfoService.updatePassword(userId, oldPassword, newPassword);

        // 全部端 Token 必须被清空（多端登录场景：只清当前端等于没清）
        verify(redisComponet, times(1)).cleanUserTokenByUserId(userId);
    }

    @Test
    public void updatePassword_success_pushForceOffLine() {
        String userId = "U12345678901";
        String oldPassword = "oldpassword";
        String newPassword = "newpassword";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setPassword(com.easychat.utils.StringTools.encodeByMD5(oldPassword));

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);
        when(userInfoMapper.updateByUserId(any(UserInfo.class), eq(userId))).thenReturn(1);

        userInfoService.updatePassword(userId, oldPassword, newPassword);

        // 已连上的 WS 不会因为 Token 被删而自动断开，必须推帧强制下线
        verify(messageHandler, times(1)).sendMessage(argThat(dto ->
                dto != null && dto.getContactId() != null && dto.getContactId().equals(userId)));
    }

    @Test
    public void resetPasswordByEmail_success_invalidatesAllSessions() {
        String email = "test@example.com";
        String code = "123456";

        EmailVerifyCode verifyCode = new EmailVerifyCode();
        verifyCode.setEmail(email);
        verifyCode.setCode(code);
        verifyCode.setType(1);
        verifyCode.setStatus(0);
        verifyCode.setExpireTime(System.currentTimeMillis() + 10 * 60 * 1000L);

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678901");
        userInfo.setEmail(email);

        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class)))
                .thenReturn(Collections.singletonList(verifyCode));
        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);
        when(userInfoMapper.updateByUserId(any(UserInfo.class), anyString())).thenReturn(1);

        userInfoService.resetPasswordByEmail(email, code, "newpassword");

        verify(redisComponet, times(1)).cleanUserTokenByUserId("U12345678901");
    }

    @Test
    public void resetPasswordByEmail_codeNotFound_doesNotInvalidateSessions() {
        // 守卫：验证码错误属校验失败，此时**绝不能**吊销会话，
        // 否则任何人输错验证码就能把受害者踢下线（反而成了 DoS）。
        when(emailVerifyCodeMapper.selectList(any(EmailVerifyCodeQuery.class)))
                .thenReturn(new ArrayList<>());

        try {
            userInfoService.resetPasswordByEmail("test@example.com", "wrongcode", "newpassword");
            fail("期望抛 BusinessException");
        } catch (BusinessException expected) {
            // 断言不变量
        }

        verify(redisComponet, never()).cleanUserTokenByUserId(anyString());
    }

    // ======================== 更新用户状态 ========================

    @Test
    public void updateUserStatus_success() {
        String userId = "U12345678901";
        Integer status = UserStatusEnum.DISABLE.getStatus();

        when(userInfoMapper.updateByUserId(any(UserInfo.class), eq(userId))).thenReturn(1);

        userInfoService.updateUserStatus(status, userId);

        verify(userInfoMapper).updateByUserId(any(UserInfo.class), eq(userId));
    }

    @Test(expected = BusinessException.class)
    public void updateUserStatus_invalidStatus() {
        String userId = "U12345678901";
        Integer invalidStatus = 999;

        userInfoService.updateUserStatus(invalidStatus, userId);
    }

    // ======================== 强制下线 ========================

    @Test
    public void forceOffLine_success() {
        String userId = "U12345678901";

        userInfoService.forceOffLine(userId);

        verify(messageHandler).sendMessage(any());
    }

    // ======================== 查询方法 ========================

    @Test
    public void getUserInfoByUserId_success() {
        String userId = "U12345678901";
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setNickName("测试用户");

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);

        UserInfo result = userInfoService.getUserInfoByUserId(userId);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
    }

    @Test
    public void getUserInfoByEmail_success() {
        String email = "test@example.com";
        UserInfo userInfo = new UserInfo();
        userInfo.setEmail(email);
        userInfo.setNickName("测试用户");

        when(userInfoMapper.selectByEmail(email)).thenReturn(userInfo);

        UserInfo result = userInfoService.getUserInfoByEmail(email);

        assertNotNull(result);
        assertEquals(email, result.getEmail());
    }

    @Test
    public void findListByParam_success() {
        com.easychat.entity.query.UserInfoQuery query = new com.easychat.entity.query.UserInfoQuery();
        List<UserInfo> userList = new ArrayList<>();
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U12345678901");
        userList.add(userInfo);

        when(userInfoMapper.selectList(query)).thenReturn(userList);

        List<UserInfo> result = userInfoService.findListByParam(query);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void findCountByParam_success() {
        com.easychat.entity.query.UserInfoQuery query = new com.easychat.entity.query.UserInfoQuery();

        when(userInfoMapper.selectCount(query)).thenReturn(5);

        Integer count = userInfoService.findCountByParam(query);

        assertEquals(Integer.valueOf(5), count);
    }

    // ======================== 更新加我方式（join_type）=======================
    // 覆盖 openspec/changes/2026-10-02-join-type-and-blacklist C1
    // 修复前：join_type 无任何更新入口，UserUpdateDTO 也不含该字段

    @Test
    public void updateJoinType_toZero() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateJoinType("U_self", 0);

        UserInfo captured = captureUpdatedUser();
        assertEquals(Integer.valueOf(0), captured.getJoinType());
        assertEquals("U_self", captured.getUserId());
    }

    @Test
    public void updateJoinType_toOne() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateJoinType("U_self", 1);

        assertEquals(Integer.valueOf(1), captureUpdatedUser().getJoinType());
    }

    /**
     * 护栏：updateJoinType 只写 join_type 一列，
     * 绝不能把 nickname/password/status 等一起覆盖掉。
     */
    @Test
    public void updateJoinType_onlyWritesJoinTypeColumn() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateJoinType("U_self", 0);

        UserInfo captured = captureUpdatedUser();
        assertNull("昵称不得被 join_type 更新覆盖", captured.getNickName());
        assertNull("密码不得被 join_type 更新覆盖", captured.getPassword());
        assertNull("状态不得被 join_type 更新覆盖", captured.getStatus());
        assertNull("性别不得被 join_type 更新覆盖", captured.getSex());
    }

    @Test
    public void updateJoinType_illegalValue_rejected() {
        for (Integer illegal : new Integer[]{2, -1, 99}) {
            try {
                userInfoService.updateJoinType("U_self", illegal);
                org.junit.Assert.fail("joinType=" + illegal + " 应抛 CODE_1001");
            } catch (BusinessException e) {
                assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
            }
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    @Test
    public void updateJoinType_nullValue_rejected() {
        try {
            userInfoService.updateJoinType("U_self", null);
            org.junit.Assert.fail("joinType 为 null 应抛 CODE_1001");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    /**
     * 护栏：用户不存在 → CODE_2101，且不落库。
     */
    @Test
    public void updateJoinType_userNotFound() {
        when(userInfoMapper.selectByUserId("U_ghost")).thenReturn(null);
        try {
            userInfoService.updateJoinType("U_ghost", 0);
            org.junit.Assert.fail("用户不存在时应抛 CODE_2101");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_2101.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    private UserInfo existingUser() {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U_self");
        userInfo.setNickName("原昵称");
        userInfo.setJoinType(1);
        return userInfo;
    }

    // ======================== 朋友圈可见范围（隐私设置）=======================
    // 覆盖 openspec/changes/2026-10-02-privacy-moment-and-status C1

    @Test
    public void updateMomentVisibility_public() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        stubFriends("U_f1", "U_f2");

        userInfoService.updateMomentPrivacy("U_self", 0, null, null);

        UserInfo captured = captureUpdatedUser();
        assertEquals(Integer.valueOf(0), captured.getMomentVisibility());
    }

    @Test
    public void updateMomentVisibility_friendsOnly() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        stubFriends("U_f1");

        userInfoService.updateMomentPrivacy("U_self", 1, null, null);

        assertEquals(Integer.valueOf(1), captureUpdatedUser().getMomentVisibility());
    }

    @Test
    public void updateMomentVisibility_selfOnly() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateMomentPrivacy("U_self", 2, null, null);

        assertEquals(Integer.valueOf(2), captureUpdatedUser().getMomentVisibility());
    }

    @Test
    public void updateMomentVisibility_whiteList() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        stubFriends("U_f1", "U_f2");

        userInfoService.updateMomentPrivacy("U_self", 3, "[\"U_f1\",\"U_f2\"]", null);

        UserInfo captured = captureUpdatedUser();
        assertEquals(Integer.valueOf(3), captured.getMomentVisibility());
        assertTrue("白名单应落 JSON 数组", captured.getMomentVisibleList().contains("U_f1"));
    }

    @Test
    public void updateMomentVisibility_blackList() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        stubFriends("U_f1");

        userInfoService.updateMomentPrivacy("U_self", 4, null, "[\"U_f1\"]");

        UserInfo captured = captureUpdatedUser();
        assertEquals(Integer.valueOf(4), captured.getMomentVisibility());
        assertTrue("黑名单应落 JSON 数组", captured.getMomentInvisibleList().contains("U_f1"));
    }

    /**
     * 白名单模式却没给名单 → 拒绝。否则「白名单=空」会让所有人都看不到。
     */
    @Test
    public void updateMomentPrivacy_whiteListRequiredWhenVisibility3() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        try {
            userInfoService.updateMomentPrivacy("U_self", 3, null, null);
            org.junit.Assert.fail("visibility=3 但白名单为空应抛 CODE_1001");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    @Test
    public void updateMomentPrivacy_blackListRequiredWhenVisibility4() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        for (String empty : new String[]{null, "", "[]"}) {
            try {
                userInfoService.updateMomentPrivacy("U_self", 4, null, empty);
                org.junit.Assert.fail("visibility=4 但黑名单为空(" + empty + ")应抛 CODE_1001");
            } catch (BusinessException e) {
                assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
            }
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    /**
     * 安全/数据质量：名单里塞非好友 id 必须被拒，防止任意 userId 混入名单。
     */
    @Test
    public void updateMomentPrivacy_nonFriendRejected() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        stubFriends("U_f1");
        try {
            userInfoService.updateMomentPrivacy("U_self", 3, "[\"U_f1\",\"U_stranger\"]", null);
            org.junit.Assert.fail("名单含非好友应抛 CODE_1001");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    @Test
    public void updateMomentPrivacy_illegalVisibility() {
        for (Integer bad : new Integer[]{-1, 5, 99, null}) {
            try {
                userInfoService.updateMomentPrivacy("U_self", bad, null, null);
                org.junit.Assert.fail("visibility=" + bad + " 应抛 CODE_1001");
            } catch (BusinessException e) {
                assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
            }
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    @Test
    public void updateMomentPrivacy_oversizedListRejected() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());
        // 每项形如 "U_f1234", 约 10 字符；需 8000 项才超过 60000 上限
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 8000; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"U_f").append(i).append("\"");
        }
        sb.append("]");
        String huge = sb.toString();
        assertTrue("构造的超长名单应超过上限 60000，实际 " + huge.length(), huge.length() > 60000);
        // 不 stub 好友：超长校验在「查好友」之前就应拒绝，避免无用 stub
        try {
            userInfoService.updateMomentPrivacy("U_self", 3, huge, null);
            org.junit.Assert.fail("超长名单应抛 CODE_1001");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    @Test
    public void updateMomentPrivacy_userNotFound() {
        when(userInfoMapper.selectByUserId("U_ghost")).thenReturn(null);
        try {
            userInfoService.updateMomentPrivacy("U_ghost", 0, null, null);
            org.junit.Assert.fail("用户不存在应抛 CODE_2101");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_2101.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    /**
     * 护栏：只写这 3 列，不得覆盖昵称/密码/加我方式。
     */
    @Test
    public void updateMomentPrivacy_onlyWritesPrivacyColumns() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateMomentPrivacy("U_self", 0, null, null);

        UserInfo captured = captureUpdatedUser();
        assertNull("昵称不得被覆盖", captured.getNickName());
        assertNull("密码不得被覆盖", captured.getPassword());
        assertNull("加我方式不得被覆盖", captured.getJoinType());
        assertNull("在线状态可见性不得被本方法写", captured.getOnlineStatusVisible());
    }

    // ======================== 在线状态可见性 ========================

    @Test
    public void updateOnlineStatusVisible_hide() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateOnlineStatusVisible("U_self", 0);

        assertEquals(Integer.valueOf(0), captureUpdatedUser().getOnlineStatusVisible());
    }

    @Test
    public void updateOnlineStatusVisible_show() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateOnlineStatusVisible("U_self", 1);

        assertEquals(Integer.valueOf(1), captureUpdatedUser().getOnlineStatusVisible());
    }

    @Test
    public void updateOnlineStatusVisible_illegalValue() {
        for (Integer bad : new Integer[]{-1, 2, null}) {
            try {
                userInfoService.updateOnlineStatusVisible("U_self", bad);
                org.junit.Assert.fail("visible=" + bad + " 应抛 CODE_1001");
            } catch (BusinessException e) {
                assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
            }
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    @Test
    public void updateOnlineStatusVisible_userNotFound() {
        when(userInfoMapper.selectByUserId("U_ghost")).thenReturn(null);
        try {
            userInfoService.updateOnlineStatusVisible("U_ghost", 0);
            org.junit.Assert.fail("用户不存在应抛 CODE_2101");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_2101.getCode(), e.getCode());
        }
        verify(userInfoMapper, never()).updateByUserId(any(UserInfo.class), anyString());
    }

    /** 护栏：只写 onlineStatusVisible 一列 */
    @Test
    public void updateOnlineStatusVisible_onlyWritesThatColumn() {
        when(userInfoMapper.selectByUserId("U_self")).thenReturn(existingUser());

        userInfoService.updateOnlineStatusVisible("U_self", 0);

        UserInfo captured = captureUpdatedUser();
        assertNull("昵称不得被覆盖", captured.getNickName());
        assertNull("朋友圈可见范围不得被本方法写", captured.getMomentVisibility());
    }

    /** 构造好友集合（一次性查询，名单做子集断言） */
    private void stubFriends(String... friendIds) {
        List<UserContact> friends = new ArrayList<>();
        for (String fid : friendIds) {
            UserContact c = new UserContact();
            c.setUserId("U_self");
            c.setContactId(fid);
            c.setContactType(0);
            c.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            friends.add(c);
        }
        when(userContactService.findListByParam(any(UserContactQuery.class))).thenReturn(friends);
    }

    private UserInfo captureUpdatedUser() {
        ArgumentCaptor<UserInfo> captor = ArgumentCaptor.forClass(UserInfo.class);
        verify(userInfoMapper).updateByUserId(captor.capture(), eq("U_self"));
        return captor.getValue();
    }
}
