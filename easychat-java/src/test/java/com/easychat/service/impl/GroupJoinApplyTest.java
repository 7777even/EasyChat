package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.JoinTypeEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.UserContactApplyService;
import com.easychat.service.UserContactService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 群二维码 / 群邀请入群路径的单元测试。
 *
 * 覆盖 openspec/changes/2026-10-02-group-join-approval 的核心修复：
 * 两条入群路径原先直接 {@code userContactService.addContact(...)} 入群，
 * 完全绕过 {@code group_info.join_type}（群主设了「需管理员同意」形同虚设）。
 * 修复后统一委托 {@link UserContactApplyService#applyAdd}，复用既有审批链路。
 *
 * <p>本类刻意<b>不</b>断言 joinByQrCode/joinByInvite 的返回值类型，
 * 只断言「入群语义已交出 applyAdd」这一可观测行为，
 * 以便在改造前就能编译并观察红色。
 * 返回值（joinType）的验证见 {@code GroupJoinApplyJoinTypeTest}。
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupJoinApplyTest {

    private static final String GROUP_ID = "G_2001";
    private static final String TOKEN = "tok_2001";

    /** applyAdd 的 contactType 入参约定：枚举名（UserContactTypeEnum.getByName 走 valueOf(upper)） */
    private static final String CONTACT_TYPE_GROUP = "GROUP";

    @InjectMocks
    private GroupQrCodeServiceImpl groupQrCodeService;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private UserContactService userContactService;

    @Mock
    private UserContactApplyService userContactApplyService;

    // ======================== 二维码入群 ========================

    /**
     * 核心护栏：扫码入群**不得**由二维码服务自行入群，必须委托 applyAdd
     * 以便受 join_type 约束。
     *
     * 修复前：joinByQrCode 直接 addContact 入群 → 本用例为红。
     */
    @Test
    public void joinByQrCode_delegatesToApplyAdd() {
        stubToken(TOKEN, GROUP_ID);
        stubGroupExists();
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                anyString(), anyString())).thenReturn(JoinTypeEnum.APPLY.getType());

        groupQrCodeService.joinByQrCode(tokenUser("U_join"), TOKEN);

        // 入群语义交出：QR 服务不得自行入群
        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
        // 且确实走了审批链路，contactType 为群
        verify(userContactApplyService).applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq(CONTACT_TYPE_GROUP), anyString());
    }

    /**
     * 群 join_type=0（直接加入）时同样走 applyAdd（由它完成入群），QR 服务不自行入群。
     */
    @Test
    public void joinByQrCode_joinTypeZeroStillDelegates() {
        stubToken(TOKEN, GROUP_ID);
        stubGroupExists();
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                anyString(), anyString())).thenReturn(JoinTypeEnum.JOIN.getType());

        groupQrCodeService.joinByQrCode(tokenUser("U_join"), TOKEN);

        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    /**
     * token 过期或无效：不得产生任何申请、不得入群。
     */
    @Test
    public void joinByQrCode_invalidToken() {
        when(redisComponet.getGroupIdByToken(TOKEN)).thenReturn(null);

        try {
            groupQrCodeService.joinByQrCode(tokenUser("U_join"), TOKEN);
            fail("无效 token 应抛业务异常");
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains("二维码已过期或无效"));
        }

        verify(userContactApplyService, never()).applyAdd(any(), anyString(), anyString(), anyString());
        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    /**
     * 群不存在：不得入群、不得产生申请。
     */
    @Test
    public void joinByQrCode_groupNotFound() {
        when(redisComponet.getGroupIdByToken(TOKEN)).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);

        try {
            groupQrCodeService.joinByQrCode(tokenUser("U_join"), TOKEN);
            fail("群不存在时应抛业务异常");
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains("群组不存在"));
        }

        verify(userContactApplyService, never()).applyAdd(any(), anyString(), anyString(), anyString());
    }

    /**
     * 已是群成员：不得重复入群、不得产生申请。
     */
    @Test
    public void joinByQrCode_alreadyMember() {
        stubToken(TOKEN, GROUP_ID);
        stubGroupExists();
        UserContact member = new UserContact();
        member.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        when(userContactMapper.selectByUserIdAndContactId("U_join", GROUP_ID)).thenReturn(member);

        try {
            groupQrCodeService.joinByQrCode(tokenUser("U_join"), TOKEN);
            fail("已是群成员时应抛业务异常");
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains("您已经是该群成员"));
        }

        verify(userContactApplyService, never()).applyAdd(any(), anyString(), anyString(), anyString());
    }

    // ======================== 邀请链接入群 ========================

    /**
     * 邀请链接路径同样必须委托 applyAdd（与二维码路径对称，原先同样绕过 join_type）。
     */
    @Test
    public void joinByInvite_delegatesToApplyAdd() {
        GroupInviteServiceImpl inviteService = newInviteService();
        when(redisComponet.getGroupIdByToken(TOKEN)).thenReturn(GROUP_ID);
        stubGroupExists();
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                anyString(), anyString())).thenReturn(JoinTypeEnum.APPLY.getType());

        inviteService.joinByInvite(tokenUser("U_join"), TOKEN);

        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
        verify(userContactApplyService).applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq(CONTACT_TYPE_GROUP), anyString());
    }

    /**
     * 邀请 token 过期：不得入群。
     */
    @Test
    public void joinByInvite_invalidToken() {
        GroupInviteServiceImpl inviteService = newInviteService();
        when(redisComponet.getGroupIdByToken(TOKEN)).thenReturn(null);

        try {
            inviteService.joinByInvite(tokenUser("U_join"), TOKEN);
            fail("无效 token 应抛业务异常");
        } catch (BusinessException e) {
            assertTrue(e.getMessage().contains("邀请链接已过期或无效"));
        }

        verify(userContactApplyService, never()).applyAdd(any(), anyString(), anyString(), anyString());
    }

    // ======================== 夹具 ========================

    private GroupInviteServiceImpl newInviteService() {
        GroupInviteServiceImpl inviteService = new GroupInviteServiceImpl();
        set(inviteService, "redisComponet", redisComponet);
        set(inviteService, "groupInfoMapper", groupInfoMapper);
        set(inviteService, "userContactMapper", userContactMapper);
        set(inviteService, "userContactService", userContactService);
        set(inviteService, "userContactApplyService", userContactApplyService);
        return inviteService;
    }

    /** 反射注入（@Resource 私有字段，测试中无 Spring 容器） */
    private void set(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入测试依赖失败: " + field, e);
        }
    }

    private void stubToken(String token, String groupId) {
        when(redisComponet.getGroupIdByToken(token)).thenReturn(groupId);
    }

    private void stubGroupExists() {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(GROUP_ID);
        groupInfo.setGroupOwnerId("U_owner");
        groupInfo.setJoinType(JoinTypeEnum.APPLY.getType());
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupInfo);
    }

    private TokenUserInfoDto tokenUser(String userId) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(userId);
        dto.setNickName("测试用户");
        return dto;
    }
}
