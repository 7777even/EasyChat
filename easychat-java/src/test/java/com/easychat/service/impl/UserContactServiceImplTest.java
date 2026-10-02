package com.easychat.service.impl;

import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.UserContactSearchResultDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.*;
import com.easychat.entity.query.*;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.*;
import com.easychat.redis.RedisComponet;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
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
 * UserContactServiceImpl 单元测试
 */
@RunWith(MockitoJUnitRunner.class)
public class UserContactServiceImplTest {

    @InjectMocks
    private UserContactServiceImpl userContactService;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Mock
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    @Mock
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Mock
    private MessageHandler messageHandler;

    @Mock
    private ChannelContextUtils channelContextUtils;

    // ======================== 搜索联系人 ========================

    @Test
    public void searchContact_userNotFound() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        when(userInfoMapper.selectByUserId(contactId)).thenReturn(null);

        UserContactSearchResultDto result = userContactService.searchContact(userId, contactId);

        assertNull(result);
    }

    @Test
    public void searchContact_userFound() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(contactId);
        userInfo.setNickName("测试用户");

        when(userInfoMapper.selectByUserId(contactId)).thenReturn(userInfo);
        when(userContactMapper.selectByUserIdAndContactId(userId, contactId)).thenReturn(null);

        UserContactSearchResultDto result = userContactService.searchContact(userId, contactId);

        assertNotNull(result);
        assertEquals(contactId, result.getContactId());
    }

    @Test
    public void searchContact_groupNotFound() {
        String userId = "U12345678901";
        String groupId = "G99999999999";

        when(groupInfoMapper.selectByGroupId(groupId)).thenReturn(null);

        UserContactSearchResultDto result = userContactService.searchContact(userId, groupId);

        assertNull(result);
    }

    @Test
    public void searchContact_groupFound() {
        String userId = "U12345678901";
        String groupId = "G99999999999";

        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(groupId);
        groupInfo.setGroupName("测试群");

        when(groupInfoMapper.selectByGroupId(groupId)).thenReturn(groupInfo);

        UserContactSearchResultDto result = userContactService.searchContact(userId, groupId);

        assertNotNull(result);
        assertEquals(groupId, result.getContactId());
    }

    @Test
    public void searchContact_selfSearch() {
        String userId = "U12345678901";

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setNickName("我自己");

        when(userInfoMapper.selectByUserId(userId)).thenReturn(userInfo);

        UserContactSearchResultDto result = userContactService.searchContact(userId, userId);

        assertNotNull(result);
        assertEquals(UserContactStatusEnum.FRIEND.getStatus(), result.getStatus());
    }

    // ======================== 添加联系人 ========================

    @Test
    public void addContact_userSuccess() {
        String applyUserId = "U12345678901";
        String receiveUserId = "U99999999999";
        String contactId = receiveUserId;
        Integer contactType = UserContactTypeEnum.USER.getType();
        String applyInfo = "你好，我想加你为好友";

        UserInfo applyUser = new UserInfo();
        applyUser.setUserId(applyUserId);
        applyUser.setNickName("申请人");

        UserInfo receiveUser = new UserInfo();
        receiveUser.setUserId(receiveUserId);
        receiveUser.setNickName("接收人");

        when(userInfoMapper.selectByUserId(receiveUserId)).thenReturn(receiveUser);
        when(userInfoMapper.selectByUserId(applyUserId)).thenReturn(applyUser);
        when(userContactMapper.insertOrUpdateBatch(anyList())).thenReturn(2);
        when(chatSessionMapper.insertOrUpdate(any(ChatSession.class))).thenReturn(1);
        when(chatSessionUserMapper.insertOrUpdateBatch(anyList())).thenReturn(2);
        when(chatMessageMapper.insert(any(ChatMessage.class))).thenReturn(1);

        userContactService.addContact(applyUserId, receiveUserId, contactId, contactType, applyInfo);

        verify(userContactMapper).insertOrUpdateBatch(anyList());
        verify(chatSessionMapper).insertOrUpdate(any(ChatSession.class));
        verify(chatMessageMapper).insert(any(ChatMessage.class));
        verify(messageHandler, times(2)).sendMessage(any());
    }

    @Test
    public void addContact_groupSuccess() {
        String applyUserId = "U12345678901";
        String groupId = "G99999999999";
        Integer contactType = UserContactTypeEnum.GROUP.getType();

        SysSettingDto sysSettingDto = new SysSettingDto();
        sysSettingDto.setMaxGroupMemberCount(500);

        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(groupId);
        groupInfo.setGroupName("测试群");

        UserInfo applyUser = new UserInfo();
        applyUser.setUserId(applyUserId);
        applyUser.setNickName("申请人");

        when(redisComponet.getSysSetting()).thenReturn(sysSettingDto);
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(10);
        when(groupInfoMapper.selectByGroupId(groupId)).thenReturn(groupInfo);
        when(userInfoMapper.selectByUserId(applyUserId)).thenReturn(applyUser);
        when(userContactMapper.insertOrUpdateBatch(anyList())).thenReturn(1);
        when(chatSessionUserMapper.insertOrUpdate(any(ChatSessionUser.class))).thenReturn(1);
        when(chatSessionMapper.insertOrUpdate(any(ChatSession.class))).thenReturn(1);
        when(chatMessageMapper.insert(any(ChatMessage.class))).thenReturn(1);

        userContactService.addContact(applyUserId, null, groupId, contactType, null);

        verify(userContactMapper).insertOrUpdateBatch(anyList());
        verify(channelContextUtils).addUser2Group(applyUserId, groupId);
        verify(messageHandler).sendMessage(any());
    }

    @Test(expected = BusinessException.class)
    public void addContact_groupFull() {
        String applyUserId = "U12345678901";
        String groupId = "G99999999999";
        Integer contactType = UserContactTypeEnum.GROUP.getType();

        SysSettingDto sysSettingDto = new SysSettingDto();
        sysSettingDto.setMaxGroupMemberCount(10);

        when(redisComponet.getSysSetting()).thenReturn(sysSettingDto);
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(10);

        userContactService.addContact(applyUserId, null, groupId, contactType, null);
    }

    // ======================== 删除联系人 ========================

    @Test
    public void removeUserContact_delete() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        when(userContactMapper.updateByUserIdAndContactId(any(UserContact.class), eq(userId), eq(contactId)))
                .thenReturn(1);

        userContactService.removeUserContact(userId, contactId, UserContactStatusEnum.DEL);

        verify(userContactMapper, times(2)).updateByUserIdAndContactId(any(UserContact.class), anyString(), anyString());
        verify(redisComponet, times(2)).removeUserContact(anyString(), anyString());
    }

    @Test
    public void removeUserContact_blacklist() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        userContactService.removeUserContact(userId, contactId, UserContactStatusEnum.BLACKLIST);

        // 修复：拉黑改走 insertOrUpdate（upsert）。
        // 原实现用 updateByUserIdAndContactId，对「搜索到的陌生人」这种尚无关系行的情况是 no-op，
        // 拉黑会静默失效（2026-10-02 由活体冒烟 smoke_blacklist.py 发现）。
        ArgumentCaptor<UserContact> captor = ArgumentCaptor.forClass(UserContact.class);
        verify(userContactMapper, times(2)).insertOrUpdate(captor.capture());

        List<UserContact> rows = captor.getAllValues();
        // 第 1 条：我拉黑他
        assertEquals(userId, rows.get(0).getUserId());
        assertEquals(contactId, rows.get(0).getContactId());
        assertEquals(UserContactStatusEnum.BLACKLIST.getStatus(), rows.get(0).getStatus());
        // 第 2 条：他那边标记「被拉黑」
        assertEquals(contactId, rows.get(1).getUserId());
        assertEquals(userId, rows.get(1).getContactId());
        assertEquals(UserContactStatusEnum.BLACKLIST_BE.getStatus(), rows.get(1).getStatus());

        // 拉黑不再走 update 路径
        verify(userContactMapper, never()).updateByUserIdAndContactId(any(UserContact.class), anyString(), anyString());
    }

    /**
     * 回归护栏：删除好友（DEL）语义未被 upsert 改动波及，仍走 update。
     */
    @Test
    public void removeUserContact_delStillUsesUpdate() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        userContactService.removeUserContact(userId, contactId, UserContactStatusEnum.DEL);

        verify(userContactMapper, times(2)).updateByUserIdAndContactId(any(UserContact.class), anyString(), anyString());
        verify(userContactMapper, never()).insertOrUpdate(any(UserContact.class));
    }

    @Test
    public void removeGroupContact_success() {
        String userId = "U12345678901";
        String groupId = "G99999999999";
        String contactId = "U88888888888";

        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(groupId);
        groupInfo.setGroupOwnerId(userId);

        when(groupInfoMapper.selectByGroupId(groupId)).thenReturn(groupInfo);
        when(userContactMapper.updateByUserIdAndContactId(any(UserContact.class), eq(contactId), eq(groupId)))
                .thenReturn(1);

        userContactService.removeGroupContact(userId, groupId, contactId, UserContactStatusEnum.DEL);

        verify(userContactMapper).updateByUserIdAndContactId(any(UserContact.class), eq(contactId), eq(groupId));
        verify(redisComponet).removeUserContact(contactId, groupId);
    }

    @Test(expected = BusinessException.class)
    public void removeGroupContact_notOwner() {
        String userId = "U12345678901";
        String groupId = "G99999999999";
        String contactId = "U88888888888";

        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(groupId);
        groupInfo.setGroupOwnerId("U00000000000"); // 不是当前用户

        when(groupInfoMapper.selectByGroupId(groupId)).thenReturn(groupInfo);

        userContactService.removeGroupContact(userId, groupId, contactId, UserContactStatusEnum.DEL);
    }

    // ======================== 设置备注 ========================

    @Test
    public void setContactRemark_success() {
        String userId = "U12345678901";
        String contactId = "U99999999999";
        String remark = "新备注";

        UserContact userContact = new UserContact();
        userContact.setUserId(userId);
        userContact.setContactId(contactId);
        userContact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        userContact.setContactName("原昵称");

        when(userContactMapper.selectByUserIdAndContactId(userId, contactId)).thenReturn(userContact);
        when(userContactMapper.updateByUserIdAndContactId(any(UserContact.class), eq(userId), eq(contactId)))
                .thenReturn(1);
        when(chatSessionUserMapper.updateByParam(any(ChatSessionUser.class), any(ChatSessionUserQuery.class)))
                .thenReturn(1);

        userContactService.setContactRemark(userId, contactId, remark);

        verify(userContactMapper).updateByUserIdAndContactId(any(UserContact.class), eq(userId), eq(contactId));
        verify(chatSessionUserMapper).updateByParam(any(ChatSessionUser.class), any(ChatSessionUserQuery.class));
    }

    @Test(expected = BusinessException.class)
    public void setContactRemark_notFriend() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        when(userContactMapper.selectByUserIdAndContactId(userId, contactId)).thenReturn(null);

        userContactService.setContactRemark(userId, contactId, "备注");
    }

    // ======================== 设置分组 ========================

    @Test
    public void setContactGroup_success() {
        String userId = "U12345678901";
        String contactId = "U99999999999";
        String groupName = "同事";

        UserContact userContact = new UserContact();
        userContact.setUserId(userId);
        userContact.setContactId(contactId);
        userContact.setStatus(UserContactStatusEnum.FRIEND.getStatus());

        when(userContactMapper.selectByUserIdAndContactId(userId, contactId)).thenReturn(userContact);
        when(userContactMapper.updateByUserIdAndContactId(any(UserContact.class), eq(userId), eq(contactId)))
                .thenReturn(1);

        userContactService.setContactGroup(userId, contactId, groupName);

        verify(userContactMapper).updateByUserIdAndContactId(any(UserContact.class), eq(userId), eq(contactId));
    }

    @Test(expected = BusinessException.class)
    public void setContactGroup_notFriend() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        when(userContactMapper.selectByUserIdAndContactId(userId, contactId)).thenReturn(null);

        userContactService.setContactGroup(userId, contactId, "同事");
    }

    // ======================== 搜索好友 ========================

    @Test
    public void searchContactByKeyword_emptyKeyword() {
        String userId = "U12345678901";

        List<UserContact> result = userContactService.searchContactByKeyword(userId, "");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void searchContactByKeyword_matchRemark() {
        String userId = "U12345678901";
        String keyword = "同事";

        UserContact contact1 = new UserContact();
        contact1.setContactId("U11111111111");
        contact1.setContactName("张三");
        contact1.setRemark("同事");
        contact1.setGroupName("朋友");

        UserContact contact2 = new UserContact();
        contact2.setContactId("U22222222222");
        contact2.setContactName("李四");
        contact2.setRemark("同学");
        contact2.setGroupName("朋友");

        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(contact1));

        List<UserContact> result = userContactService.searchContactByKeyword(userId, keyword);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("U11111111111", result.get(0).getContactId());
    }

    @Test
    public void searchContactByKeyword_matchNickName() {
        String userId = "U12345678901";
        String keyword = "张三";

        UserContact contact1 = new UserContact();
        contact1.setContactId("U11111111111");
        contact1.setContactName("张三");
        contact1.setRemark(null);
        contact1.setGroupName(null);

        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(contact1));

        List<UserContact> result = userContactService.searchContactByKeyword(userId, keyword);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void searchContactByKeyword_matchGroupName() {
        String userId = "U12345678901";
        String keyword = "同事";

        UserContact contact1 = new UserContact();
        contact1.setContactId("U11111111111");
        contact1.setContactName("张三");
        contact1.setRemark(null);
        contact1.setGroupName("同事");

        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(contact1));

        List<UserContact> result = userContactService.searchContactByKeyword(userId, keyword);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void searchContactByKeyword_noMatch() {
        String userId = "U12345678901";
        String keyword = "不存在的关键词";

        UserContact contact1 = new UserContact();
        contact1.setContactId("U11111111111");
        contact1.setContactName("张三");
        contact1.setRemark("同学");
        contact1.setGroupName("朋友");

        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(contact1));

        List<UserContact> result = userContactService.searchContactByKeyword(userId, keyword);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ======================== 查询方法 ========================

    @Test
    public void getUserContactByUserIdAndContactId_success() {
        String userId = "U12345678901";
        String contactId = "U99999999999";

        UserContact userContact = new UserContact();
        userContact.setUserId(userId);
        userContact.setContactId(contactId);
        userContact.setStatus(UserContactStatusEnum.FRIEND.getStatus());

        when(userContactMapper.selectByUserIdAndContactId(userId, contactId)).thenReturn(userContact);

        UserContact result = userContactService.getUserContactByUserIdAndContactId(userId, contactId);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertEquals(contactId, result.getContactId());
    }

    @Test
    public void findListByParam_success() {
        UserContactQuery query = new UserContactQuery();
        List<UserContact> contactList = new ArrayList<>();
        UserContact contact = new UserContact();
        contact.setUserId("U12345678901");
        contactList.add(contact);

        when(userContactMapper.selectList(query)).thenReturn(contactList);

        List<UserContact> result = userContactService.findListByParam(query);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void findCountByParam_success() {
        UserContactQuery query = new UserContactQuery();

        when(userContactMapper.selectCount(query)).thenReturn(5);

        Integer count = userContactService.findCountByParam(query);

        assertEquals(Integer.valueOf(5), count);
    }
}
