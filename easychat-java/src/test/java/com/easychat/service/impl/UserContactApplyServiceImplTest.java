package com.easychat.service.impl;

import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactApplyStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.po.UserContactApply;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactApplyQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactApplyMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;
import com.easychat.websocket.MessageHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserContactApplyServiceImpl 单元测试
 *
 * 覆盖 openspec/changes/2026-10-02-group-join-approval 的审批权限分流：
 * 群入群申请（contactType=GROUP）审批人由「仅群主」放宽为「群主或群管理员」；
 * 好友申请（contactType=USER）审批人仍为 receive_user_id 本人，不得被放宽。
 */
@RunWith(MockitoJUnitRunner.class)
public class UserContactApplyServiceImplTest {

    /** 申请附言：dealWithApply 同意时作为 addContact 的 applyInfo 透传 */
    private static final String APPLY_INFO = "我是测试申请人";

    @InjectMocks
    private UserContactApplyServiceImpl userContactApplyService;

    @Mock
    private UserContactApplyMapper<UserContactApply, UserContactApplyQuery> userContactApplyMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Mock
    private MessageHandler messageHandler;

    @Mock
    private UserContactService userContactService;

    @Mock
    private GroupInfoService groupInfoService;

    // ======================== dealWithApply：群入群申请 ========================

    /**
     * 群管理员（非群主）应能审批入群申请。
     *
     * 修复前 dealWithApply 硬性要求 userId == receiveUserId（群主），
     * 管理员调用会抛 CODE_1001，故本用例在修复前为红。
     */
    @Test
    public void dealWithApply_groupApply_adminCanApprove() {
        UserContactApply apply = groupApply(9001, "G_1001");
        when(userContactApplyMapper.selectByApplyId(9001)).thenReturn(apply);
        when(groupInfoService.checkGroupRole(eq("U_admin"), eq("G_1001"), eq(GroupMemberRoleEnum.ADMIN)))
                .thenReturn(memberInGroup(GroupMemberRoleEnum.ADMIN));
        when(userContactApplyMapper.updateByParam(any(UserContactApply.class), any(UserContactApplyQuery.class)))
                .thenReturn(1);

        userContactApplyService.dealWithApply("U_admin", 9001, UserContactApplyStatusEnum.PASS.getStatus());

        verify(userContactService).addContact(eq("U_applicant"), eq("U_owner"), eq("G_1001"),
                eq(UserContactTypeEnum.GROUP.getType()), eq(APPLY_INFO));
    }

    /**
     * 群主本人审批入群申请必须仍然可用（回归护栏：不能因放宽而把群主挡在门外）。
     */
    @Test
    public void dealWithApply_groupApply_ownerCanApprove() {
        UserContactApply apply = groupApply(9002, "G_1002");
        when(userContactApplyMapper.selectByApplyId(9002)).thenReturn(apply);
        // 群主 role=OWNER(0) 亦满足 checkGroupRole(ADMIN) 的阈值语义（0 群主 > 1 管理员）
        when(groupInfoService.checkGroupRole(eq("U_owner"), eq("G_1002"), eq(GroupMemberRoleEnum.ADMIN)))
                .thenReturn(memberInGroup(GroupMemberRoleEnum.OWNER));
        when(userContactApplyMapper.updateByParam(any(UserContactApply.class), any(UserContactApplyQuery.class)))
                .thenReturn(1);

        userContactApplyService.dealWithApply("U_owner", 9002, UserContactApplyStatusEnum.PASS.getStatus());

        verify(userContactService).addContact(eq("U_applicant"), eq("U_owner"), eq("G_1002"),
                eq(UserContactTypeEnum.GROUP.getType()), eq(APPLY_INFO));
    }

    /**
     * 普通群成员无权审批入群申请，期望 CODE_2305（无权执行此操作），且不得写入状态、不得入群。
     */
    @Test
    public void dealWithApply_groupApply_memberRejected() {
        UserContactApply apply = groupApply(9003, "G_1003");
        when(userContactApplyMapper.selectByApplyId(9003)).thenReturn(apply);
        when(groupInfoService.checkGroupRole(eq("U_member"), eq("G_1003"), eq(GroupMemberRoleEnum.ADMIN)))
                .thenThrow(new BusinessException(ResponseCodeEnum.CODE_2305));

        try {
            userContactApplyService.dealWithApply("U_member", 9003, UserContactApplyStatusEnum.PASS.getStatus());
            fail("普通群成员应被拒绝审批入群申请");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }

        verify(userContactApplyMapper, never()).updateByParam(any(UserContactApply.class),
                any(UserContactApplyQuery.class));
        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    /**
     * 非群成员审批入群申请，期望 CODE_2304（已不在该群组）。
     */
    @Test
    public void dealWithApply_groupApply_nonMemberRejected() {
        UserContactApply apply = groupApply(9004, "G_1004");
        when(userContactApplyMapper.selectByApplyId(9004)).thenReturn(apply);
        when(groupInfoService.checkGroupRole(eq("U_outsider"), eq("G_1004"), eq(GroupMemberRoleEnum.ADMIN)))
                .thenThrow(new BusinessException(ResponseCodeEnum.CODE_2304));

        try {
            userContactApplyService.dealWithApply("U_outsider", 9004, UserContactApplyStatusEnum.PASS.getStatus());
            fail("非群成员应被拒绝审批入群申请");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2304.getCode(), e.getCode());
        }

        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    /**
     * 拉黑入群申请（status=BLACKLIST）：写 BLACKLIST_BE_FIRST 关系且不入群。
     */
    @Test
    public void dealWithApply_groupApply_blacklistDoesNotJoin() {
        UserContactApply apply = groupApply(9009, "G_1009");
        when(userContactApplyMapper.selectByApplyId(9009)).thenReturn(apply);
        when(groupInfoService.checkGroupRole(eq("U_owner"), eq("G_1009"), eq(GroupMemberRoleEnum.ADMIN)))
                .thenReturn(memberInGroup(GroupMemberRoleEnum.OWNER));
        when(userContactApplyMapper.updateByParam(any(UserContactApply.class), any(UserContactApplyQuery.class)))
                .thenReturn(1);

        userContactApplyService.dealWithApply("U_owner", 9009, UserContactApplyStatusEnum.BLACKLIST.getStatus());

        verify(userContactMapper).insertOrUpdate(any(UserContact.class));
        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    // ======================== dealWithApply：好友申请（不得被放宽） ========================

    /**
     * 好友申请的审批人仍必须是 receive_user_id 本人。
     *
     * 这是「群审批放宽」的负面护栏：绝不能因 contactType 分流写错，
     * 让任意人去审批别人的好友申请。
     */
    @Test
    public void dealWithApply_friendApply_stillRequiresReceiver() {
        UserContactApply apply = friendApply(9005);
        when(userContactApplyMapper.selectByApplyId(9005)).thenReturn(apply);

        try {
            userContactApplyService.dealWithApply("U_someoneElse", 9005,
                    UserContactApplyStatusEnum.PASS.getStatus());
            fail("非被申请人不应能处理好友申请");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }

        // 关键：好友申请分支绝不走 checkGroupRole（否则语义被污染）
        verify(groupInfoService, never()).checkGroupRole(anyString(), anyString(), any(GroupMemberRoleEnum.class));
        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    /**
     * 好友申请被申请人本人处理，期望正常通过。
     */
    @Test
    public void dealWithApply_friendApply_receiverCanApprove() {
        UserContactApply apply = friendApply(9006);
        when(userContactApplyMapper.selectByApplyId(9006)).thenReturn(apply);
        when(userContactApplyMapper.updateByParam(any(UserContactApply.class), any(UserContactApplyQuery.class)))
                .thenReturn(1);

        userContactApplyService.dealWithApply("U_receiver", 9006, UserContactApplyStatusEnum.PASS.getStatus());

        verify(userContactService).addContact(eq("U_applicant"), eq("U_receiver"), eq("U_target"),
                eq(UserContactTypeEnum.USER.getType()), eq(APPLY_INFO));
    }

    // ======================== dealWithApply：通用守卫 ========================

    /**
     * 申请不存在，期望 CODE_1001。
     */
    @Test
    public void dealWithApply_applyNotFound() {
        when(userContactApplyMapper.selectByApplyId(9999)).thenReturn(null);
        try {
            userContactApplyService.dealWithApply("U_owner", 9999, UserContactApplyStatusEnum.PASS.getStatus());
            fail("申请不存在时应抛 CODE_1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    /**
     * 非法 status（INIT），期望 CODE_1001。
     */
    @Test
    public void dealWithApply_invalidStatus() {
        try {
            userContactApplyService.dealWithApply("U_owner", 9007, UserContactApplyStatusEnum.INIT.getStatus());
            fail("INIT 状态不应可作为处理动作");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    /**
     * 并发重复审批守卫：条件 update 影响 0 行时抛 CODE_1001，且不得入群。
     */
    @Test
    public void dealWithApply_concurrentDuplicateRejected() {
        UserContactApply apply = groupApply(9008, "G_1008");
        when(userContactApplyMapper.selectByApplyId(9008)).thenReturn(apply);
        when(groupInfoService.checkGroupRole(eq("U_owner"), eq("G_1008"), eq(GroupMemberRoleEnum.ADMIN)))
                .thenReturn(memberInGroup(GroupMemberRoleEnum.OWNER));
        when(userContactApplyMapper.updateByParam(any(UserContactApply.class), any(UserContactApplyQuery.class)))
                .thenReturn(0);

        try {
            userContactApplyService.dealWithApply("U_owner", 9008, UserContactApplyStatusEnum.PASS.getStatus());
            fail("并发重复审批应被拦截");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }

        verify(userContactService, never()).addContact(anyString(), any(), anyString(), anyInt(), anyString());
    }

    // ======================== 测试夹具 ========================

    private UserContactApply groupApply(Integer applyId, String groupId) {
        UserContactApply apply = new UserContactApply();
        apply.setApplyId(applyId);
        apply.setApplyUserId("U_applicant");
        apply.setReceiveUserId("U_owner");
        apply.setContactId(groupId);
        apply.setContactType(UserContactTypeEnum.GROUP.getType());
        apply.setStatus(UserContactApplyStatusEnum.INIT.getStatus());
        apply.setApplyInfo(APPLY_INFO);
        return apply;
    }

    private UserContactApply friendApply(Integer applyId) {
        UserContactApply apply = new UserContactApply();
        apply.setApplyId(applyId);
        apply.setApplyUserId("U_applicant");
        apply.setReceiveUserId("U_receiver");
        apply.setContactId("U_target");
        apply.setContactType(UserContactTypeEnum.USER.getType());
        apply.setStatus(UserContactApplyStatusEnum.INIT.getStatus());
        apply.setApplyInfo(APPLY_INFO);
        return apply;
    }

    private UserContact memberInGroup(GroupMemberRoleEnum roleEnum) {
        UserContact userContact = new UserContact();
        userContact.setRole(roleEnum.getRole());
        return userContact;
    }
}
