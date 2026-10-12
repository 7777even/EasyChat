package com.easychat.service.impl;

import com.easychat.entity.enums.BeautyAccountStatusEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.po.UserInfoBeauty;
import com.easychat.mappers.UserInfoBeautyMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.exception.BusinessException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserInfoBeautyServiceImpl 单元测试。
 *
 * <p>靓号池的 saveAccount 是管理端写路径：「已使用禁改」「邮箱 / 靓号
 * 池内唯一」「邮箱 / 靓号未被注册」四重校验漏一项会让靓号池脏数据
 * （重复占用或撞已注册账号）。此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class UserInfoBeautyServiceImplTest {

    private static final String EMAIL = "beauty@example.com";
    private static final String BARE_USER_ID = "01234567890";
    private static final String PREFIXED_USER_ID = UserContactTypeEnum.USER.getPrefix() + BARE_USER_ID;
    private static final Integer BEAUTY_ID = 1;

    @InjectMocks
    private UserInfoBeautyServiceImpl userInfoBeautyService;

    @Mock
    private UserInfoBeautyMapper<UserInfoBeauty, com.easychat.entity.query.UserInfoBeautyQuery> userInfoBeautyMapper;

    @Mock
    private UserInfoMapper<UserInfo, com.easychat.entity.query.UserInfoQuery> userInfoMapper;

    private UserInfoBeauty beautyOf(Integer id, String email, String bareUserId) {
        UserInfoBeauty beauty = new UserInfoBeauty();
        beauty.setId(id);
        beauty.setEmail(email);
        beauty.setUserId(bareUserId);
        return beauty;
    }

    // ======================== saveAccount：新建 ========================

    @Test
    public void saveAccount_create_emailExistsInPool_throwsWithMessage() {
        UserInfoBeauty beauty = beautyOf(null, EMAIL, BARE_USER_ID);
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(beautyOf(9, EMAIL, "999"));
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("邮箱已在靓号池应拒绝");
        } catch (BusinessException e) {
            assertEquals("靓号邮箱已经存在", e.getMessage());
        }
        verify(userInfoBeautyMapper, never()).insert(any(UserInfoBeauty.class));
    }

    @Test
    public void saveAccount_create_userIdExistsInPool_throwsWithMessage() {
        UserInfoBeauty beauty = beautyOf(null, EMAIL, BARE_USER_ID);
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoBeautyMapper.selectByUserId(PREFIXED_USER_ID))
                .thenReturn(beautyOf(9, "other@example.com", BARE_USER_ID));
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("靓号已在池内应拒绝");
        } catch (BusinessException e) {
            assertEquals("靓号已经存在", e.getMessage());
        }
        verify(userInfoBeautyMapper, never()).insert(any(UserInfoBeauty.class));
    }

    @Test
    public void saveAccount_create_emailRegistered_throwsWithMessage() {
        UserInfoBeauty beauty = beautyOf(null, EMAIL, BARE_USER_ID);
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoBeautyMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(new UserInfo());
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("邮箱已注册应拒绝");
        } catch (BusinessException e) {
            assertEquals("靓号邮箱已经被注册", e.getMessage());
        }
        verify(userInfoBeautyMapper, never()).insert(any(UserInfoBeauty.class));
    }

    @Test
    public void saveAccount_create_userIdRegistered_throwsWithMessage() {
        UserInfoBeauty beauty = beautyOf(null, EMAIL, BARE_USER_ID);
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoBeautyMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(new UserInfo());
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("靓号已注册应拒绝");
        } catch (BusinessException e) {
            assertEquals("靓号已经被注册", e.getMessage());
        }
        verify(userInfoBeautyMapper, never()).insert(any(UserInfoBeauty.class));
    }

    @Test
    public void saveAccount_create_success_inserts() {
        UserInfoBeauty beauty = beautyOf(null, EMAIL, BARE_USER_ID);
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoBeautyMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);

        userInfoBeautyService.saveAccount(beauty);

        verify(userInfoBeautyMapper).insert(beauty);
        verify(userInfoBeautyMapper, never()).updateById(any(UserInfoBeauty.class), any(Integer.class));
    }

    // ======================== saveAccount：编辑 ========================

    @Test
    public void saveAccount_usedBeautyCannotModify_throws1001() {
        // 已使用的靓号（status=USEED）不允许修改
        UserInfoBeauty beauty = beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID);
        UserInfoBeauty dbInfo = beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID);
        dbInfo.setStatus(BeautyAccountStatusEnum.USEED.getStatus());
        when(userInfoBeautyMapper.selectById(BEAUTY_ID)).thenReturn(dbInfo);
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("已使用靓号应拒绝修改");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoBeautyMapper, never()).updateById(any(UserInfoBeauty.class), any(Integer.class));
    }

    @Test
    public void saveAccount_update_emailTakenByOther_throwsWithMessage() {
        UserInfoBeauty beauty = beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID);
        UserInfoBeauty dbInfo = beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID);
        dbInfo.setStatus(0);
        when(userInfoBeautyMapper.selectById(BEAUTY_ID)).thenReturn(dbInfo);
        // 邮箱被池中另一条记录占用
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(beautyOf(2, EMAIL, "999"));
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("邮箱被他人占用应拒绝");
        } catch (BusinessException e) {
            assertEquals("靓号邮箱已经存在", e.getMessage());
        }
    }

    @Test
    public void saveAccount_update_success_updatesById() {
        UserInfoBeauty beauty = beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID);
        UserInfoBeauty dbInfo = beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID);
        dbInfo.setStatus(0);
        when(userInfoBeautyMapper.selectById(BEAUTY_ID)).thenReturn(dbInfo);
        // 邮箱 / 靓号都仍归本条记录所有
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID));
        when(userInfoBeautyMapper.selectByUserId(PREFIXED_USER_ID))
                .thenReturn(beautyOf(BEAUTY_ID, EMAIL, BARE_USER_ID));
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);

        userInfoBeautyService.saveAccount(beauty);

        ArgumentCaptor<UserInfoBeauty> captor = ArgumentCaptor.forClass(UserInfoBeauty.class);
        verify(userInfoBeautyMapper).updateById(captor.capture(), eq(BEAUTY_ID));
        assertEquals(EMAIL, captor.getValue().getEmail());
        verify(userInfoBeautyMapper, never()).insert(any(UserInfoBeauty.class));
    }

    // ==================== 靓号字符集约束（遗留 #19，2026-10-12）====================
    // 背景：靓号是**唯一**能把任意字符写进 user_info.user_id 的入口，且此前**零格式校验**。
    //   字符集无上界 → 客户端 @ 提及正则无法收紧（收紧成 \d+ 会把字母靓号用户彻底挡在 @ 之外）。
    // 约束须与 StringTools.getUserId()（"U" + 随机数(11)，即纯数字）严格对齐。
    // 活库已实测：靓号表 0 行、非「U+数字」的 user_id 0 条 → 收紧对存量零影响。

    /** 断言：给定靓号入参被拒（CODE_1001），且**不落库**。 */
    private void assertRejectedAndNotPersisted(String bareUserId, String why) {
        UserInfoBeauty beauty = beautyOf(null, EMAIL, bareUserId);
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("靓号字符集非法时应当拒绝：" + why + "（实际入参=" + bareUserId + "）");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        // 关键：校验必须发生在任何写库动作之前
        verify(userInfoBeautyMapper, never()).insert(any(UserInfoBeauty.class));
        verify(userInfoBeautyMapper, never())
                .updateById(any(UserInfoBeauty.class), any(Integer.class));
    }

    @Test
    public void saveAccount_lettersRejected_throws1001() {
        // 含字母的靓号会产出 userId="UABC"，超出 @ 提及正则可表达的集合
        assertRejectedAndNotPersisted("ABC", "含大写字母");
        assertRejectedAndNotPersisted("abc", "含小写字母");
        assertRejectedAndNotPersisted("888a88", "数字中夹字母");
    }

    @Test
    public void saveAccount_specialCharsRejected_throws1001() {
        assertRejectedAndNotPersisted("88-88", "含连字符");
        assertRejectedAndNotPersisted("88_88", "含下划线");
        assertRejectedAndNotPersisted("88 88", "含空格");
        assertRejectedAndNotPersisted("８８８", "含全角数字");
        assertRejectedAndNotPersisted("88.88", "含小数点");
    }

    @Test
    public void saveAccount_emptyOrNullRejected_throws1001() {
        assertRejectedAndNotPersisted("", "空串");
        assertRejectedAndNotPersisted(null, "null");
    }

    @Test
    public void saveAccount_validDigits_accepted() {
        // 正例：形态合法时仍走原有流程并落库（防止校验把正常路径一起拦掉）
        UserInfoBeauty beauty = beautyOf(null, EMAIL, BARE_USER_ID);
        when(userInfoBeautyMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoBeautyMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);
        when(userInfoMapper.selectByEmail(EMAIL)).thenReturn(null);
        when(userInfoMapper.selectByUserId(PREFIXED_USER_ID)).thenReturn(null);

        userInfoBeautyService.saveAccount(beauty);

        verify(userInfoBeautyMapper).insert(beauty);
    }

    @Test
    public void saveAccount_editWithIllegalUserId_alsoRejected() {
        // 编辑路径同样受约束（否则可先建合法号、再改成 UABC 绕过）
        UserInfoBeauty beauty = beautyOf(BEAUTY_ID, EMAIL, "ABC");
        try {
            userInfoBeautyService.saveAccount(beauty);
            fail("编辑路径也必须校验靓号字符集");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoBeautyMapper, never()).updateById(any(UserInfoBeauty.class), any(Integer.class));
    }
}
