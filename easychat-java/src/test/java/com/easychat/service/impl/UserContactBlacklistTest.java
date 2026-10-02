package com.easychat.service.impl;

import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.UserContactSearchResultDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.ChatSession;
import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.ChatSessionQuery;
import com.easychat.entity.query.ChatSessionUserQuery;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.query.UserInfoQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.entity.vo.UserInfoVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 黑名单「查 / 解」单元测试。
 *
 * 覆盖 openspec/changes/2026-10-02-join-type-and-blacklist C2 / C3。
 * 修复前：只能加黑（addContact2BlackList），既无列表也无解除，
 * 用户点错一次就永久无法退出。
 */
@RunWith(MockitoJUnitRunner.class)
public class UserContactBlacklistTest {

    private static final String ME = "U_me";
    private static final String OTHER = "U_other";

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

    // ======================== C2 加载黑名单 ========================

    /**
     * 黑名单查询条件必须精确：只查我拉黑的（status=4）、只查好友维度、按最近拉黑倒序。
     */
    @Test
    public void loadBlackList_queryScopeIsExact() {
        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(blackRow(ME, OTHER)));

        userContactService.loadBlackList(ME);

        ArgumentCaptor<UserContactQuery> captor = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactMapper).selectList(captor.capture());
        UserContactQuery q = captor.getValue();

        assertEquals("只能查自己的黑名单", ME, q.getUserId());
        assertEquals("只查好友维度（群组不入黑名单）",
                UserContactTypeEnum.USER.getType(), q.getContactType());
        assertNotNull("statusArray 必须限定", q.getStatusArray());
        assertEquals("只含 BLACKLIST(4)，不含 BLACKLIST_BE(5)",
                1, q.getStatusArray().length);
        assertEquals(UserContactStatusEnum.BLACKLIST.getStatus(), Integer.valueOf(q.getStatusArray()[0]));
        assertTrue("需联查对方昵称", q.getQueryContactUserInfo());
        assertEquals("按最近拉黑倒序", "last_update_time desc", q.getOrderBy());
    }

    @Test
    public void loadBlackList_emptyListIsNotAnError() {
        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.<UserContact>emptyList());

        List<UserContact> result = userContactService.loadBlackList(ME);

        assertNotNull(result);
        assertTrue("空黑名单应返回空列表而非抛错", result.isEmpty());
    }

    @Test
    public void loadBlackList_returnsRows() {
        UserContact row = blackRow(ME, OTHER);
        row.setContactName("被拉黑的人");
        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(row));

        List<UserContact> result = userContactService.loadBlackList(ME);

        assertEquals(1, result.size());
        assertEquals("被拉黑的人", result.get(0).getContactName());
        assertEquals(OTHER, result.get(0).getContactId());
    }

    // ======================== C3 解除黑名单 ========================

    /**
     * 正常解除：我的拉黑行删除 + 对方「被拉黑」行也删除 + 双向清缓存。
     * <p>
     * 拉黑是双向写的（我→他=4 BLACKLIST，他→我=5 BLACKLIST_BE），
     * 只删自己那行会留下「我已解除、对方仍显示被拉黑」的单向不一致。
     */
    @Test
    public void removeBlackList_removesBothDirectionsAndClearsCache() {
        stubMyRow(UserContactStatusEnum.BLACKLIST);
        stubOtherRow(UserContactStatusEnum.BLACKLIST_BE);

        userContactService.removeBlackList(ME, OTHER);

        verify(userContactMapper).deleteByUserIdAndContactId(ME, OTHER);
        verify(userContactMapper).deleteByUserIdAndContactId(OTHER, ME);
        verify(redisComponet).removeUserContact(OTHER, ME);
        verify(redisComponet).removeUserContact(ME, OTHER);
    }

    /**
     * 安全红线：目标是我 status=5（他拉黑了我）时，我**无权**解除。
     * <p>
     * 守卫若只判「行存在」而不判 status，我会单方解除别人对我的拉黑。
     */
    @Test
    public void removeBlackList_cannotRemoveOthersBlacklistOnMe() {
        stubMyRow(UserContactStatusEnum.BLACKLIST_BE);

        try {
            userContactService.removeBlackList(ME, OTHER);
            fail("他拉黑我的关系行不应可被解除");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_2401.getCode(), e.getCode());
        }

        verify(userContactMapper, never()).deleteByUserIdAndContactId(anyString(), anyString());
        verify(redisComponet, never()).removeUserContact(anyString(), anyString());
    }

    /**
     * 目标完全不在我的联系人表里 → 2401，不删任何行。
     */
    @Test
    public void removeBlackList_targetNotRelated() {
        when(userContactMapper.selectByUserIdAndContactId(ME, OTHER)).thenReturn(null);

        try {
            userContactService.removeBlackList(ME, OTHER);
            fail("无关系行时应抛 CODE_2401");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_2401.getCode(), e.getCode());
        }

        verify(userContactMapper, never()).deleteByUserIdAndContactId(anyString(), anyString());
    }

    /**
     * 对方也拉黑了我（双向拉黑）时，我只删自己的行，对方的拉黑记录保留——
     * 那是他的处置权，不归我管。
     */
    @Test
    public void removeBlackList_keepsOtherDirectionWhenMutuallyBlacklisted() {
        stubMyRow(UserContactStatusEnum.BLACKLIST);
        // 反向行是 BLACKLIST(4) 而非 BLACKLIST_BE(5)，说明对方也拉黑了我
        stubOtherRow(UserContactStatusEnum.BLACKLIST);

        userContactService.removeBlackList(ME, OTHER);

        verify(userContactMapper).deleteByUserIdAndContactId(ME, OTHER);
        verify(userContactMapper, never()).deleteByUserIdAndContactId(OTHER, ME);
    }

    /**
     * 重复解除幂等：第二次目标已无 BLACKLIST 行 → 2401，不误删。
     */
    @Test
    public void removeBlackList_idempotent() {
        stubMyRow(UserContactStatusEnum.BLACKLIST);
        stubOtherRow(UserContactStatusEnum.BLACKLIST_BE);
        userContactService.removeBlackList(ME, OTHER);

        // 第二次：行已被删
        when(userContactMapper.selectByUserIdAndContactId(ME, OTHER)).thenReturn(null);
        try {
            userContactService.removeBlackList(ME, OTHER);
            fail("重复解除应抛 CODE_2401");
        } catch (BusinessException e) {
            assertEquals(com.easychat.entity.enums.ResponseCodeEnum.CODE_2401.getCode(), e.getCode());
        }

        // 第二次调用在守卫处即抛 2401，未走到 DELETE：自己的行只被删 1 次，反向行同样只删 1 次
        verify(userContactMapper, times(1)).deleteByUserIdAndContactId(ME, OTHER);
        verify(userContactMapper, times(1)).deleteByUserIdAndContactId(OTHER, ME);
    }

    /**
     * 护栏：解除只影响 (我, 他) 这一对，不得触碰第三方关系行。
     */
    @Test
    public void removeBlackList_doesNotTouchOtherRows() {
        stubMyRow(UserContactStatusEnum.BLACKLIST);
        stubOtherRow(UserContactStatusEnum.BLACKLIST_BE);

        userContactService.removeBlackList(ME, OTHER);

        ArgumentCaptor<String> userIds = ArgumentCaptor.forClass(String.class);
        verify(userContactMapper, times(2)).deleteByUserIdAndContactId(userIds.capture(), anyString());
        for (String id : userIds.getAllValues()) {
            assertTrue("只允许操作 " + ME + " / " + OTHER + "，实际 " + id,
                    ME.equals(id) || OTHER.equals(id));
        }
    }

    // ======================== 夹具 ========================

    private void stubMyRow(UserContactStatusEnum status) {
        when(userContactMapper.selectByUserIdAndContactId(ME, OTHER)).thenReturn(blackRow(ME, OTHER, status));
    }

    private void stubOtherRow(UserContactStatusEnum status) {
        when(userContactMapper.selectByUserIdAndContactId(OTHER, ME)).thenReturn(blackRow(OTHER, ME, status));
    }

    private UserContact blackRow(String userId, String contactId) {
        return blackRow(userId, contactId, UserContactStatusEnum.BLACKLIST);
    }

    private UserContact blackRow(String userId, String contactId, UserContactStatusEnum status) {
        UserContact userContact = new UserContact();
        userContact.setUserId(userId);
        userContact.setContactId(contactId);
        userContact.setContactType(UserContactTypeEnum.USER.getType());
        userContact.setStatus(status.getStatus());
        return userContact;
    }
}
