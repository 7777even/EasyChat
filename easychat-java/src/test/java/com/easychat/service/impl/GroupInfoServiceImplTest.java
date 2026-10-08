package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.GroupStatusEnum;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.ChatSession;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.ChatSessionQuery;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.ChatSessionUserService;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactService;
import com.easychat.utils.StringTools;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GroupInfoServiceImpl 单元测试。
 *
 * <p>本服务是建群 / 加群审批 / 退群 / 解散 / 角色变更 / 禁言 / 群公告的核心面，
 * 此前为零覆盖（system-facts §14 #7 登记的 18 个零覆盖 Service 之一）。
 * 这里重点锁定「权限闸门与状态流转」语义——这类逻辑条件漏一项即静默失效，
 * 且角色数值约定（0 群主 &gt; 1 管理员 &gt; 2 成员，数值越大权限越小）
 * 极易在后续改动中被反转。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupInfoServiceImplTest {

    private static final String OWNER = "U_owner";
    private static final String ADMIN = "U_admin";
    private static final String MEMBER = "U_member";
    private static final String GROUP_ID = "G001";
    private static final String GROUP_SESSION_ID = StringTools.getChatSessionId4Group(GROUP_ID);

    @InjectMocks
    private GroupInfoServiceImpl groupInfoService;

    /** 服务内 @Lazy 自注入的代理（addOrRemoveGroupUser 经它调用 leaveGroup，保证事务语义） */
    @Mock
    private GroupInfoService groupInfoServiceProxy;

    @Mock
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Mock
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private ChatSessionUserService chatSessionUserService;

    @Mock
    private MessageHandler messageHandler;

    @Mock
    private ChannelContextUtils channelContextUtils;

    @Mock
    private UserContactService userContactService;

    @Mock
    private AppConfig appConfig;

    // ======================== 工具方法 ========================

    private TokenUserInfoDto tokenOf(String userId, String nickName) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(userId);
        dto.setNickName(nickName);
        return dto;
    }

    private GroupInfo groupOf(String ownerId) {
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(GROUP_ID);
        groupInfo.setGroupOwnerId(ownerId);
        groupInfo.setGroupName("测试群");
        return groupInfo;
    }

    private UserContact contactOf(String userId, Integer role) {
        UserContact contact = new UserContact();
        contact.setUserId(userId);
        contact.setContactId(GROUP_ID);
        contact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        contact.setRole(role);
        return contact;
    }

    private void stubMember(String userId, Integer role) {
        when(userContactMapper.selectByUserIdAndContactId(eq(userId), eq(GROUP_ID)))
                .thenReturn(contactOf(userId, role));
    }

    // ======================== checkGroupRole：权限总闸门 ========================

    @Test
    public void checkGroupRole_notInGroup_throws2304() {
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        try {
            groupInfoService.checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.MEMBER);
            fail("非群成员应抛 2304");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2304.getCode(), e.getCode());
        }
    }

    @Test
    public void checkGroupRole_contactNotFriend_throws2304() {
        // 状态为「已删除好友」的残留行不算在群内
        UserContact contact = contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        contact.setStatus(UserContactStatusEnum.DEL.getStatus());
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(contact);
        try {
            groupInfoService.checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.MEMBER);
            fail("非好友状态应抛 2304");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2304.getCode(), e.getCode());
        }
    }

    @Test
    public void checkGroupRole_nullRole_defaultsToMember() {
        // role 为 null 的存量行按「成员」处理：对成员门槛放行，对管理员门槛拒绝
        UserContact contact = contactOf(MEMBER, null);
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(contact);

        UserContact passed = groupInfoService.checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.MEMBER);
        assertEquals(contact, passed);

        try {
            groupInfoService.checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.ADMIN);
            fail("role 为 null 应按成员对待、对管理员门槛抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void checkGroupRole_memberBelowAdminGate_throws2305() {
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        try {
            groupInfoService.checkGroupRole(MEMBER, GROUP_ID, GroupMemberRoleEnum.ADMIN);
            fail("成员(2) > 管理员(1) 应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void checkGroupRole_ownerPassesAdminGate() {
        UserContact contact = contactOf(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        when(userContactMapper.selectByUserIdAndContactId(OWNER, GROUP_ID)).thenReturn(contact);
        // 群主(0) 对管理员(1) 门槛：0 <= 1，放行并返回该行
        UserContact result = groupInfoService.checkGroupRole(OWNER, GROUP_ID, GroupMemberRoleEnum.ADMIN);
        assertEquals(contact, result);
    }

    // ======================== setAdmin：设置 / 取消管理员 ========================

    @Test
    public void setAdmin_byMember_throws2305() {
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        try {
            groupInfoService.setAdmin(tokenOf(MEMBER, "成员"), GROUP_ID, ADMIN, GroupMemberRoleEnum.ADMIN);
            fail("仅群主可设置管理员，应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void setAdmin_self_throws2306() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        try {
            groupInfoService.setAdmin(tokenOf(OWNER, "群主"), GROUP_ID, OWNER, GroupMemberRoleEnum.ADMIN);
            fail("群主不能操作自己，应抛 2306");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2306.getCode(), e.getCode());
        }
    }

    @Test
    public void setAdmin_targetNotMember_throws2304() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        when(userContactMapper.selectByUserIdAndContactId(ADMIN, GROUP_ID)).thenReturn(null);
        try {
            groupInfoService.setAdmin(tokenOf(OWNER, "群主"), GROUP_ID, ADMIN, GroupMemberRoleEnum.ADMIN);
            fail("目标不在群内应抛 2304");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2304.getCode(), e.getCode());
        }
    }

    @Test
    public void setAdmin_targetIsOwner_throws2306() {
        // 操作者是群主、目标行角色也是群主（异常数据）：不能操作其他群主
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        stubMember(ADMIN, GroupMemberRoleEnum.OWNER.getRole());
        try {
            groupInfoService.setAdmin(tokenOf(OWNER, "群主"), GROUP_ID, ADMIN, GroupMemberRoleEnum.ADMIN);
            fail("目标为群主应抛 2306");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2306.getCode(), e.getCode());
        }
        verify(userContactMapper, never()).updateRole(anyString(), anyString(), any());
    }

    @Test
    public void setAdmin_ownerSetsAdmin_updatesRole() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        stubMember(ADMIN, GroupMemberRoleEnum.MEMBER.getRole());
        groupInfoService.setAdmin(tokenOf(OWNER, "群主"), GROUP_ID, ADMIN, GroupMemberRoleEnum.ADMIN);
        verify(userContactMapper).updateRole(ADMIN, GROUP_ID, GroupMemberRoleEnum.ADMIN.getRole());
    }

    // ======================== muteMember：禁言 ========================

    @Test
    public void muteMember_byMember_throws2305() {
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        try {
            groupInfoService.muteMember(tokenOf(MEMBER, "成员"), GROUP_ID, ADMIN, 5);
            fail("群主与管理员才可禁言，成员应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void muteMember_self_throws2306() {
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        try {
            groupInfoService.muteMember(tokenOf(ADMIN, "管理员"), GROUP_ID, ADMIN, 5);
            fail("不能禁言自己，应抛 2306");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2306.getCode(), e.getCode());
        }
    }

    @Test
    public void muteMember_adminMutesOwner_throws2306() {
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        try {
            groupInfoService.muteMember(tokenOf(ADMIN, "管理员"), GROUP_ID, OWNER, 5);
            fail("管理员不可禁言群主，应抛 2306");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2306.getCode(), e.getCode());
        }
    }

    @Test
    public void muteMember_adminMutesAdmin_throws2305() {
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        stubMember(MEMBER, GroupMemberRoleEnum.ADMIN.getRole());
        try {
            groupInfoService.muteMember(tokenOf(ADMIN, "管理员"), GROUP_ID, MEMBER, 5);
            fail("管理员不可禁言其他管理员，应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void muteMember_ownerMutesAdmin_endTimeIsNowPlusMinutes() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        long before = System.currentTimeMillis();
        groupInfoService.muteMember(tokenOf(OWNER, "群主"), GROUP_ID, ADMIN, 5);
        ArgumentCaptor<Date> dateCaptor = ArgumentCaptor.forClass(Date.class);
        verify(userContactMapper).updateMuteEndTime(eq(ADMIN), eq(GROUP_ID), dateCaptor.capture());
        long delta = dateCaptor.getValue().getTime() - before;
        long expected = 5L * 60 * 1000;
        // 允许 2 秒执行误差：禁言结束时间 = 当前时间 + 5 分钟
        assertTrue("禁言结束时间应约为 now + 5 分钟，实际偏差 " + delta + "ms",
                delta >= expected - 2000 && delta <= expected + 2000);
    }

    @Test
    public void muteMember_zeroMinutes_clearsMute() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        // minutes <= 0 视为解除禁言（muteEndTime 落 null）
        groupInfoService.muteMember(tokenOf(OWNER, "群主"), GROUP_ID, ADMIN, 0);
        verify(userContactMapper).updateMuteEndTime(ADMIN, GROUP_ID, null);
    }

    // ======================== transferOwner：转让群主 ========================

    @Test
    public void transferOwner_byNonOwner_throws2305() {
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        try {
            groupInfoService.transferOwner(tokenOf(MEMBER, "成员"), GROUP_ID, ADMIN);
            fail("仅群主可转让，应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void transferOwner_toSelf_throws1001() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        try {
            groupInfoService.transferOwner(tokenOf(OWNER, "群主"), GROUP_ID, OWNER);
            fail("不能转让给自己，应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void transferOwner_newOwnerNotMember_throws2304() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        when(userContactMapper.selectByUserIdAndContactId(ADMIN, GROUP_ID)).thenReturn(null);
        try {
            groupInfoService.transferOwner(tokenOf(OWNER, "群主"), GROUP_ID, ADMIN);
            fail("新群主必须是群成员，应抛 2304");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2304.getCode(), e.getCode());
        }
    }

    @Test
    public void transferOwner_success_swapsRolesAndUpdatesGroup() {
        stubMember(OWNER, GroupMemberRoleEnum.OWNER.getRole());
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        groupInfoService.transferOwner(tokenOf(OWNER, "群主"), GROUP_ID, MEMBER);

        // 新群主 role -> 0
        ArgumentCaptor<UserContact> newOwnerCaptor = ArgumentCaptor.forClass(UserContact.class);
        verify(userContactMapper).updateByUserIdAndContactId(newOwnerCaptor.capture(), eq(MEMBER), eq(GROUP_ID));
        assertEquals(GroupMemberRoleEnum.OWNER.getRole(), newOwnerCaptor.getValue().getRole());

        // 原群主 role -> 2（降为成员）
        ArgumentCaptor<UserContact> oldOwnerCaptor = ArgumentCaptor.forClass(UserContact.class);
        verify(userContactMapper).updateByUserIdAndContactId(oldOwnerCaptor.capture(), eq(OWNER), eq(GROUP_ID));
        assertEquals(GroupMemberRoleEnum.MEMBER.getRole(), oldOwnerCaptor.getValue().getRole());

        // 群表群主字段更新
        ArgumentCaptor<GroupInfo> groupCaptor = ArgumentCaptor.forClass(GroupInfo.class);
        verify(groupInfoMapper).updateByGroupId(groupCaptor.capture(), eq(GROUP_ID));
        assertEquals(MEMBER, groupCaptor.getValue().getGroupOwnerId());

        // 会话冗余字段刷新
        verify(chatSessionUserService).updateRedundanceInfo(null, GROUP_ID);
    }

    // ======================== editGroupNotice：群公告 ========================

    @Test
    public void editGroupNotice_byMember_throws2305() {
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        try {
            groupInfoService.editGroupNotice(tokenOf(MEMBER, "成员"), GROUP_ID, "公告");
            fail("群主或管理员才可编辑公告，成员应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void editGroupNotice_groupMissing_throws2303() {
        // 权限校验在前：操作者是管理员，但群已被删除 → 2303
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        try {
            groupInfoService.editGroupNotice(tokenOf(ADMIN, "管理员"), GROUP_ID, "公告");
            fail("群不存在应抛 2303");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2303.getCode(), e.getCode());
        }
    }

    @Test
    public void editGroupNotice_success_persistsAndBroadcasts() {
        stubMember(ADMIN, GroupMemberRoleEnum.ADMIN.getRole());
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        groupInfoService.editGroupNotice(tokenOf(ADMIN, "管理员乙"), GROUP_ID, "新公告内容");

        // 群表公告字段更新
        ArgumentCaptor<GroupInfo> groupCaptor = ArgumentCaptor.forClass(GroupInfo.class);
        verify(groupInfoMapper).updateByGroupId(groupCaptor.capture(), eq(GROUP_ID));
        assertEquals("新公告内容", groupCaptor.getValue().getGroupNotice());

        // 落库一条 GROUP_NOTICE 系统消息
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper).insert(messageCaptor.capture());
        ChatMessage message = messageCaptor.getValue();
        assertEquals(MessageTypeEnum.GROUP_NOTICE.getType(), message.getMessageType());
        assertEquals("管理员乙更新了群公告", message.getMessageContent());
        assertEquals(ADMIN, message.getSendUserId());
        assertEquals("管理员乙", message.getSendUserNickName());
        assertEquals(GROUP_ID, message.getContactId());
        assertEquals(UserContactTypeEnum.GROUP.getType(), message.getContactType());

        // 会话最后一条消息同步
        verify(chatSessionMapper).updateBySessionId(any(ChatSession.class), eq(GROUP_SESSION_ID));

        // WS 广播：extendData 携带公告原文
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(messageHandler).sendMessage(dtoCaptor.capture());
        assertEquals("新公告内容", dtoCaptor.getValue().getExtendData());
        assertEquals("管理员乙更新了群公告", dtoCaptor.getValue().getLastMessage());
    }

    // ======================== leaveGroup：退群 ========================

    @Test
    public void leaveGroup_groupMissing_throws1001() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        try {
            groupInfoService.leaveGroup(MEMBER, GROUP_ID, MessageTypeEnum.LEAVE_GROUP);
            fail("群不存在应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void leaveGroup_ownerCannotLeave_throws1001() {
        // 创建者不能退出群聊，只能解散群
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        try {
            groupInfoService.leaveGroup(OWNER, GROUP_ID, MessageTypeEnum.LEAVE_GROUP);
            fail("群主退群应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userContactMapper, never()).deleteByUserIdAndContactId(anyString(), anyString());
    }

    @Test
    public void leaveGroup_notMember_throws1001() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.deleteByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(0);
        try {
            groupInfoService.leaveGroup(MEMBER, GROUP_ID, MessageTypeEnum.LEAVE_GROUP);
            fail("非群成员退群应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void leaveGroup_success_updatesSessionAndBroadcasts() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        when(userContactMapper.deleteByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(1);
        UserInfo userInfo = new UserInfo();
        userInfo.setNickName("成员甲");
        when(userInfoMapper.selectByUserId(MEMBER)).thenReturn(userInfo);
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(2);

        groupInfoService.leaveGroup(MEMBER, GROUP_ID, MessageTypeEnum.LEAVE_GROUP);

        // 会话最后一条消息：「成员甲退出了群聊」
        ArgumentCaptor<ChatSession> sessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).updateBySessionId(sessionCaptor.capture(), eq(GROUP_SESSION_ID));
        assertEquals("成员甲退出了群聊", sessionCaptor.getValue().getLastMessage());

        // 消息落库
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper).insert(messageCaptor.capture());
        ChatMessage message = messageCaptor.getValue();
        assertEquals(MessageTypeEnum.LEAVE_GROUP.getType(), message.getMessageType());
        assertEquals("成员甲退出了群聊", message.getMessageContent());
        assertEquals(GROUP_ID, message.getContactId());
        assertEquals(GROUP_SESSION_ID, message.getSessionId());

        // WS 推送携带剩余成员数与退出者
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(messageHandler).sendMessage(dtoCaptor.capture());
        assertEquals(Integer.valueOf(2), dtoCaptor.getValue().getMemberCount());
        assertEquals(MEMBER, dtoCaptor.getValue().getExtendData());
    }

    // ======================== dissolutionGroup：解散群 ========================

    @Test
    public void dissolutionGroup_byNonOwner_throws1001() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        try {
            groupInfoService.dissolutionGroup(MEMBER, GROUP_ID);
            fail("仅群主可解散群，应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void dissolutionGroup_success_marksAllContactsDeleted() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        List<UserContact> contacts = Arrays.asList(
                contactOf(OWNER, GroupMemberRoleEnum.OWNER.getRole()),
                contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole()));
        when(userContactMapper.selectList(any(UserContactQuery.class))).thenReturn(contacts);

        groupInfoService.dissolutionGroup(OWNER, GROUP_ID);

        // 群状态 -> DISSOLUTION
        ArgumentCaptor<GroupInfo> groupCaptor = ArgumentCaptor.forClass(GroupInfo.class);
        verify(groupInfoMapper).updateByGroupId(groupCaptor.capture(), eq(GROUP_ID));
        assertEquals(GroupStatusEnum.DISSOLUTION.getStatus(), groupCaptor.getValue().getStatus());

        // 全部群成员联系人 -> DEL
        ArgumentCaptor<UserContact> contactCaptor = ArgumentCaptor.forClass(UserContact.class);
        verify(userContactMapper).updateByParam(contactCaptor.capture(), any(UserContactQuery.class));
        assertEquals(UserContactStatusEnum.DEL.getStatus(), contactCaptor.getValue().getStatus());

        // 逐个清 Redis 群联系人缓存
        verify(redisComponet).removeUserContact(OWNER, GROUP_ID);
        verify(redisComponet).removeUserContact(MEMBER, GROUP_ID);

        // 会话最后消息 + 解散系统消息落库 + WS 推送
        verify(chatSessionMapper).updateBySessionId(any(ChatSession.class), eq(GROUP_SESSION_ID));
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper).insert(messageCaptor.capture());
        assertEquals(MessageTypeEnum.DISSOLUTION_GROUP.getType(), messageCaptor.getValue().getMessageType());
        assertEquals("群聊已解散", messageCaptor.getValue().getMessageContent());
        verify(messageHandler).sendMessage(any(MessageSendDto.class));
    }

    // ======================== addOrRemoveGroupUser：群主批量加减人 ========================

    @Test
    public void addOrRemoveGroupUser_byNonOwner_throws2305() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        try {
            groupInfoService.addOrRemoveGroupUser(tokenOf(MEMBER, "成员"), GROUP_ID, "U1", Constants.ZERO);
            fail("仅群主可加减人，应抛 2305");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void addOrRemoveGroupUser_remove_delegatesLeaveGroup() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        // opType=0 移除：逐个委托给 leaveGroup（REMOVE_GROUP），经自注入代理保证事务
        groupInfoService.addOrRemoveGroupUser(tokenOf(OWNER, "群主"), GROUP_ID, "U1,U2", Constants.ZERO);
        verify(groupInfoServiceProxy).leaveGroup("U1", GROUP_ID, MessageTypeEnum.REMOVE_GROUP);
        verify(groupInfoServiceProxy).leaveGroup("U2", GROUP_ID, MessageTypeEnum.REMOVE_GROUP);
    }

    @Test
    public void addOrRemoveGroupUser_add_delegatesAddContact() {
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        // opType=1 添加：委托给 addContact（以群类型加好友）
        groupInfoService.addOrRemoveGroupUser(tokenOf(OWNER, "群主"), GROUP_ID, "U1", Constants.ONE);
        verify(userContactService).addContact("U1", null, GROUP_ID, UserContactTypeEnum.GROUP.getType(), null);
    }

    // ======================== checkMuted：发送前禁言检查 ========================

    @Test
    public void checkMuted_nullArgs_silent() {
        // 空参数直接放行（防御性短路，不抛异常）
        groupInfoService.checkMuted(null, GROUP_ID);
        groupInfoService.checkMuted(MEMBER, null);
    }

    @Test
    public void checkMuted_noMuteOrExpired_silent() {
        // 无禁言记录
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        groupInfoService.checkMuted(MEMBER, GROUP_ID);

        // 禁言已过期
        UserContact expired = contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        expired.setMuteEndTime(new Date(System.currentTimeMillis() - 60 * 1000L));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(expired);
        groupInfoService.checkMuted(MEMBER, GROUP_ID);
    }

    @Test
    public void checkMuted_activeMute_throws2307() {
        UserContact muted = contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        muted.setMuteEndTime(new Date(System.currentTimeMillis() + 60 * 1000L));
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(muted);
        try {
            groupInfoService.checkMuted(MEMBER, GROUP_ID);
            fail("禁言未到期应抛 2307");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2307.getCode(), e.getCode());
        }
    }

    // ======================== getGroupMemberList：成员列表 ========================

    @Test
    public void getGroupMemberList_notMember_throws2304() {
        when(userContactMapper.selectByUserIdAndContactId(MEMBER, GROUP_ID)).thenReturn(null);
        try {
            groupInfoService.getGroupMemberList(tokenOf(MEMBER, "成员"), GROUP_ID);
            fail("非群成员不可查看成员列表，应抛 2304");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2304.getCode(), e.getCode());
        }
    }

    @Test
    public void getGroupMemberList_success_queryShapeLocked() {
        stubMember(MEMBER, GroupMemberRoleEnum.MEMBER.getRole());
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(3);
        when(userContactMapper.selectList(any(UserContactQuery.class))).thenReturn(Arrays.asList(
                contactOf(OWNER, GroupMemberRoleEnum.OWNER.getRole()),
                contactOf(ADMIN, GroupMemberRoleEnum.ADMIN.getRole()),
                contactOf(MEMBER, GroupMemberRoleEnum.MEMBER.getRole())));

        PaginationResultVO<UserContact> result = groupInfoService.getGroupMemberList(tokenOf(MEMBER, "成员"), GROUP_ID);

        // 查询条件：群类型 + 好友状态 + 角色优先排序 + 50 条页
        ArgumentCaptor<UserContactQuery> queryCaptor = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactMapper).selectCount(queryCaptor.capture());
        UserContactQuery query = queryCaptor.getValue();
        assertEquals(GROUP_ID, query.getContactId());
        assertEquals(UserContactTypeEnum.GROUP.getType(), query.getContactType());
        assertEquals(UserContactStatusEnum.FRIEND.getStatus(), query.getStatus());
        assertEquals(SortOption.USER_CONTACT_ROLE_THEN_CREATE_TIME_ASC, query.getSortOption());

        assertEquals(Integer.valueOf(3), result.getTotalCount());
        assertEquals(3, result.getList().size());
    }

    // ======================== saveGroup：建群 / 编辑群 ========================

    @Test
    public void saveGroup_create_atLimit_throwsWithMessage() {
        GroupInfo bean = new GroupInfo();
        bean.setGroupOwnerId(OWNER);
        bean.setGroupName("测试群");
        when(groupInfoMapper.selectCount(any(GroupInfoQuery.class))).thenReturn(3);
        SysSettingDto sysSettingDto = new SysSettingDto();
        sysSettingDto.setMaxGroupCount(3);
        when(redisComponet.getSysSetting()).thenReturn(sysSettingDto);
        try {
            groupInfoService.saveGroup(bean, null, null);
            fail("达到建群上限应拒绝");
        } catch (BusinessException e) {
            assertEquals("最多只能创建3个群聊", e.getMessage());
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void saveGroup_create_missingAvatar_throws1001() {
        GroupInfo bean = new GroupInfo();
        bean.setGroupOwnerId(OWNER);
        bean.setGroupName("测试群");
        when(groupInfoMapper.selectCount(any(GroupInfoQuery.class))).thenReturn(0);
        SysSettingDto sysSettingDto = new SysSettingDto();
        sysSettingDto.setMaxGroupCount(5);
        when(redisComponet.getSysSetting()).thenReturn(sysSettingDto);
        try {
            groupInfoService.saveGroup(bean, null, null);
            fail("新建群头像必传，应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void saveGroup_create_success_writesOwnerContactAndCreateMessage() {
        GroupInfo bean = new GroupInfo();
        bean.setGroupOwnerId(OWNER);
        bean.setGroupName("测试群");
        when(groupInfoMapper.selectCount(any(GroupInfoQuery.class))).thenReturn(0);
        SysSettingDto sysSettingDto = new SysSettingDto();
        sysSettingDto.setMaxGroupCount(5);
        when(redisComponet.getSysSetting()).thenReturn(sysSettingDto);
        // 新建群头像必传：落盘到临时目录（避开真实项目目录），测试后清理
        java.nio.file.Path tempFolder;
        try {
            tempFolder = java.nio.file.Files.createTempDirectory("easychat-save-group-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建临时目录失败", e);
        }
        when(appConfig.getProjectFolder()).thenReturn(tempFolder.toString() + "/");
        MockMultipartFile avatarFile = new MockMultipartFile(
                "avatarFile", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        try {
            groupInfoService.saveGroup(bean, avatarFile, null);
        } finally {
            deleteRecursively(tempFolder.toFile());
        }

        // 群 ID 由服务端生成
        ArgumentCaptor<GroupInfo> groupCaptor = ArgumentCaptor.forClass(GroupInfo.class);
        verify(groupInfoMapper).insert(groupCaptor.capture());
        String groupId = groupCaptor.getValue().getGroupId();
        assertNotNull("群 ID 由服务端生成", groupId);

        // 群主以 OWNER 角色、群类型、好友状态写入 user_contact
        ArgumentCaptor<UserContact> contactCaptor = ArgumentCaptor.forClass(UserContact.class);
        verify(userContactMapper).insert(contactCaptor.capture());
        UserContact ownerContact = contactCaptor.getValue();
        assertEquals(OWNER, ownerContact.getUserId());
        assertEquals(groupId, ownerContact.getContactId());
        assertEquals(UserContactTypeEnum.GROUP.getType(), ownerContact.getContactType());
        assertEquals(UserContactStatusEnum.FRIEND.getStatus(), ownerContact.getStatus());
        assertEquals(GroupMemberRoleEnum.OWNER.getRole(), ownerContact.getRole());

        // 群创建系统消息落库
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper).insert(messageCaptor.capture());
        ChatMessage message = messageCaptor.getValue();
        assertEquals(MessageTypeEnum.GROUP_CREATE.getType(), message.getMessageType());
        assertEquals(groupId, message.getContactId());

        // Redis 缓存、WS 群内上下文、推送均使用同一 groupId
        verify(redisComponet).addUserContact(OWNER, groupId);
        verify(channelContextUtils).addUser2Group(OWNER, groupId);
        verify(messageHandler).sendMessage(any(MessageSendDto.class));
    }

    private void deleteRecursively(java.io.File file) {
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    @Test
    public void saveGroup_update_byNonOwner_throws1001() {
        GroupInfo bean = new GroupInfo();
        bean.setGroupId(GROUP_ID);
        bean.setGroupOwnerId(MEMBER);
        bean.setGroupName("改名");
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupOf(OWNER));
        try {
            groupInfoService.saveGroup(bean, null, null);
            fail("仅群主可编辑群资料，应抛 1001");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void saveGroup_update_owner_success_refreshesRedundancy() {
        GroupInfo bean = new GroupInfo();
        bean.setGroupId(GROUP_ID);
        bean.setGroupOwnerId(OWNER);
        bean.setGroupName("新群名");
        GroupInfo dbInfo = groupOf(OWNER);
        dbInfo.setGroupName("旧群名");
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(dbInfo);

        groupInfoService.saveGroup(bean, null, null);

        verify(groupInfoMapper).updateByGroupId(bean, GROUP_ID);
        // 群名变更时刷新会话冗余字段（联系人显示名）
        verify(chatSessionUserService).updateRedundanceInfo("新群名", GROUP_ID);
    }
}
