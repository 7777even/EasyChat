package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.BeautyAccountStatusEnum;
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
import com.easychat.service.OperationLogService;
import com.easychat.service.UserContactService;
import com.easychat.websocket.MessageHandler;
import com.easychat.config.EasyChatProperties;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
}
