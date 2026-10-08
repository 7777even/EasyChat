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
 * GroupQrCodeServiceImpl 单元测试。
 *
 * <p>群二维码与群邀请链接是同一契约的两个入口（生成权限仅群主/管理员、
 * 入群委托申请链路），此处锁定二维码入口的措辞与委托附言，
 * 防止两入口漂移。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupQrCodeServiceImplTest {

    private static final String OWNER = "U_owner";
    private static final String ADMIN = "U_admin";
    private static final String MEMBER = "U_member";
    private static final String GROUP_ID = "G001";

    @InjectMocks
    private GroupQrCodeServiceImpl groupQrCodeService;

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

    // ======================== generateQrCode ========================

    @Test
    public void generateQrCode_groupMissing_throwsWithMessage() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        try {
            groupQrCodeService.generateQrCode(tokenOf(OWNER), GROUP_ID);
            fail("群组不存在应拒绝");
        } catch (BusinessException e) {
            assertEquals("群组不存在", e.getMessage());
        }
    }

    @Test
    public void generateQrCode_nonOwnerNonAdmin_throwsWithMessage() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        try {
            groupQrCodeService.generateQrCode(tokenOf(MEMBER), GROUP_ID);
            fail("非群主/管理员应拒绝");
        } catch (BusinessException e) {
            assertEquals("只有群主/管理员可以生成群二维码", e.getMessage());
        }
        verify(redisComponet, never()).saveGroupQrCode(anyString(), anyString());
    }

    @Test
    public void generateQrCode_owner_savesToken() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        String token = groupQrCodeService.generateQrCode(tokenOf(OWNER), GROUP_ID);
        assertTrue("token 应为 32 位 hex，实际: " + token, token.matches("[0-9a-f]{32}"));
        verify(redisComponet).saveGroupQrCode(GROUP_ID, token);
    }

    @Test
    public void generateQrCode_admin_savesToken() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(ADMIN, GROUP_ID))
                .thenReturn(contactOf(ADMIN, GroupMemberRoleEnum.ADMIN.getRole(),
                        UserContactStatusEnum.FRIEND.getStatus()));
        String token = groupQrCodeService.generateQrCode(tokenOf(ADMIN), GROUP_ID);
        assertTrue(token.matches("[0-9a-f]{32}"));
        verify(redisComponet).saveGroupQrCode(GROUP_ID, token);
    }

    // ======================== joinByQrCode ========================

    @Test
    public void joinByQrCode_expiredToken_throwsWithMessage() {
        when(redisComponet.getGroupIdByToken("bad-token")).thenReturn(null);
        try {
            groupQrCodeService.joinByQrCode(tokenOf(MEMBER), "bad-token");
            fail("无效 token 应拒绝");
        } catch (BusinessException e) {
            assertEquals("二维码已过期或无效", e.getMessage());
        }
    }

    @Test
    public void joinByQrCode_groupMissing_throwsWithMessage() {
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        try {
            groupQrCodeService.joinByQrCode(tokenOf(MEMBER), "tok");
            fail("群组不存在应拒绝");
        } catch (BusinessException e) {
            assertEquals("群组不存在", e.getMessage());
        }
    }

    @Test
    public void joinByQrCode_alreadyMember_throwsWithMessage() {
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID))
                .thenReturn(contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole(),
                        UserContactStatusEnum.FRIEND.getStatus()));
        try {
            groupQrCodeService.joinByQrCode(tokenOf(MEMBER), "tok");
            fail("已是群成员应拒绝");
        } catch (BusinessException e) {
            assertEquals("您已经是该群成员", e.getMessage());
        }
        verify(userContactApplyService, never()).applyAdd(any(), anyString(), anyString(), anyString());
    }

    @Test
    public void joinByQrCode_delegatesToApplyAddWithSourceNote() {
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), anyString())).thenReturn(1);

        Integer result = groupQrCodeService.joinByQrCode(tokenOf(MEMBER), "tok");

        assertEquals(Integer.valueOf(1), result);
        // 附言标识来源为「群二维码」，与邀请链接入口区分
        verify(userContactApplyService).applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), eq("通过群二维码申请加入"));
    }

    @Test
    public void joinByQrCode_formerMemberCanReapply() {
        when(redisComponet.getGroupIdByToken("tok")).thenReturn(GROUP_ID);
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID))
                .thenReturn(contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole(),
                        UserContactStatusEnum.DEL.getStatus()));
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), anyString())).thenReturn(1);

        Integer result = groupQrCodeService.joinByQrCode(tokenOf(MEMBER), "tok");

        assertEquals(Integer.valueOf(1), result);
        verify(userContactApplyService).applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                eq("GROUP"), eq("通过群二维码申请加入"));
    }
}
