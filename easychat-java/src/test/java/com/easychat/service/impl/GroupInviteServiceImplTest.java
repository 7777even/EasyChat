package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.UserContactApplyService;
import com.easychat.service.UserContactService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GroupInviteServiceImpl 单元测试。
 *
 * <p>群邀请链接的「生成权限（仅群主/管理员）」与「入群委托」是其核心契约：
 * 生成权限漏判会让普通成员批量生成链接；入群自行实现会绕过
 * group_info.join_type 管辖的申请审批链路。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupInviteServiceImplTest {

    private static final String OWNER = "U_owner";
    private static final String ADMIN = "U_admin";
    private static final String MEMBER = "U_member";
    private static final String GROUP_ID = "G001";

    @InjectMocks
    private GroupInviteServiceImpl groupInviteService;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private GroupInfoMapper<GroupInfo, com.easychat.entity.query.GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private UserContactService userContactService;

    @Mock
    private UserContactApplyService userContactApplyService;

    private TokenUserInfoDto tokenOf(String userId) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(userId);
        return dto;
    }

    private GroupInfo groupOf(String ownerId) {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(GROUP_ID);
        groupInfo.setGroupOwnerId(ownerId);
        return groupInfo;
    }

    private UserContact contactOf(String userId, Integer role, Integer status) {
        UserContact contact = new UserContact();
        contact.setUserId(userId);
        contact.setContactId(GROUP_ID);
        contact.setRole(role);
        contact.setStatus(status);
        return contact;
    }

    // ======================== generateInvite ========================

    @Test
    public void generateInvite_groupMissing_throwsWithMessage() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        try {
            groupInviteService.generateInvite(tokenOf(OWNER), GROUP_ID);
            fail("群组不存在应拒绝");
        } catch (BusinessException e) {
            assertEquals("群组不存在", e.getMessage());
        }
    }

    @Test
    public void generateInvite_nonOwnerWithoutContact_throwsWithMessage() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        try {
            groupInviteService.generateInvite(tokenOf(MEMBER), GROUP_ID);
            fail("非群主且无成员关系应拒绝");
        } catch (BusinessException e) {
            assertEquals("只有群主/管理员可以生成邀请链接", e.getMessage());
        }
        verify(redisComponet, never()).saveGroupInvite(anyString(), anyString());
    }

    @Test
    public void generateInvite_nonOwnerMemberRole_throwsWithMessage() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID))
                .thenReturn(contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole(),
                        UserContactStatusEnum.FRIEND.getStatus()));
        try {
            groupInviteService.generateInvite(tokenOf(MEMBER), GROUP_ID);
            fail("普通成员应拒绝");
        } catch (BusinessException e) {
            assertEquals("只有群主/管理员可以生成邀请链接", e.getMessage());
        }
    }

    @Test
    public void generateInvite_owner_savesToken() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        String token = groupInviteService.generateInvite(tokenOf(OWNER), GROUP_ID);

        // UUID 去横线：32 位小写十六进制
        assertTrue("token 应为 32 位 hex，实际: " + token, token.matches("[0-9a-f]{32}"));
        verify(redisComponet).saveGroupInvite(GROUP_ID, token);
    }

    @Test
    public void generateInvite_admin_savesToken() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(ADMIN, GROUP_ID))
                .thenReturn(contactOf(ADMIN, GroupMemberRoleEnum.ADMIN.getRole(),
                        UserContactStatusEnum.FRIEND.getStatus()));
        String token = groupInviteService.generateInvite(tokenOf(ADMIN), GROUP_ID);
        assertTrue(token.matches("[0-9a-f]{32}"));
        verify(redisComponet).saveGroupInvite(GROUP_ID, token);
    }

    // ======================== joinByInvite ========================

    @Test
    public void joinByInvite_expiredToken_throwsWithMessage() {
        when(redisComponet.getGroupIdByToken("bad-token")).thenReturn(null);
        try {
            groupInviteService.joinByInvite(tokenOf(MEMBER), "bad-token");
            fail("无效 token 应拒绝");
        } catch (BusinessException e) {
            assertEquals("邀请链接已过期或无效", e.getMessage());
        }
    }

    @Test
    public void joinByInvite_groupMissing_throwsWithMessage() {
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        try {
            groupInviteService.joinByInvite(tokenOf(MEMBER), "tok");
            fail("群组不存在应拒绝");
        } catch (BusinessException e) {
            assertEquals("群组不存在", e.getMessage());
        }
    }

    @Test
    public void joinByInvite_alreadyMember_throwsWithMessage() {
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID))
                .thenReturn(contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole(),
                        UserContactStatusEnum.FRIEND.getStatus()));
        try {
            groupInviteService.joinByInvite(tokenOf(MEMBER), "tok");
            fail("已是群成员应拒绝");
        } catch (BusinessException e) {
            assertEquals("您已经是该群成员", e.getMessage());
        }
        verify(userContactApplyService, never()).applyAdd(any(), anyString(), anyString(), anyString());
    }

    @Test
    public void joinByInvite_delegatesToApplyAddWithSourceNote() {
        // 入群行为委托既有申请链路（受 join_type 管辖），不得自行入群
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), anyString())).thenReturn(1);

        Integer result = groupInviteService.joinByInvite(tokenOf(MEMBER), "tok");

        assertEquals(Integer.valueOf(1), result);
        ArgumentCaptor<String> applyInfoCaptor = ArgumentCaptor.forClass(String.class);
        verify(userContactApplyService).applyAdd(any(TokenUserInfoDto.class),
                eq(GROUP_ID), eq("GROUP"), applyInfoCaptor.capture());
        assertEquals("通过群邀请链接申请加入", applyInfoCaptor.getValue());
    }

    @Test
    public void joinByInvite_formerMemberCanReapply() {
        // 历史成员（已删除状态）不算「已是成员」，可重新走申请链路
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID))
                .thenReturn(contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole(),
                        UserContactStatusEnum.DEL.getStatus()));
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), anyString())).thenReturn(1);

        Integer result = groupInviteService.joinByInvite(tokenOf(MEMBER), "tok");

        assertEquals(Integer.valueOf(1), result);
        verify(userContactApplyService).applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), eq("通过群邀请链接申请加入"));
    }
}
