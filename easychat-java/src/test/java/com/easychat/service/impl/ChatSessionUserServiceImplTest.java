package com.easychat.service.impl;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.ChatSessionUserQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.service.ChatSessionUserService;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSessionUserServiceImpl 单元测试。
 *
 * <p>会话用户级属性（置顶 / 免打扰 / 草稿）是跨端同步面：
 * 白名单校验（仅 0/1）、会话记录存在性（1003）、落库后
 * 向本人其他在线设备广播；updateRedundanceInfo 的群 / 好友
 * 双通道广播（群一条、好友逐条）漏一路即静默不同步。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class ChatSessionUserServiceImplTest {

    private static final String USER = "U_me";
    private static final String CONTACT = "C001";
    private static final String SESSION_ID = "S001";
    private static final String GROUP_ID = "G001";
    private static final String NEW_NAME = "新昵称";

    @InjectMocks
    private ChatSessionUserServiceImpl chatSessionUserService;

    @Mock
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    @Mock
    private MessageHandler messageHandler;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private ChannelContextUtils channelContextUtils;

    private ChatSessionUser sessionUserOf(String sessionId) {
        ChatSessionUser sessionUser = new ChatSessionUser();
        sessionUser.setUserId(USER);
        sessionUser.setContactId(CONTACT);
        sessionUser.setSessionId(sessionId);
        return sessionUser;
    }

    private UserContact friendOf(String friendId) {
        UserContact contact = new UserContact();
        contact.setUserId(friendId);
        contact.setContactId(USER);
        contact.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        return contact;
    }

    // ======================== setSessionTop ========================

    @Test
    public void setSessionTop_null_throws1001() {
        try {
            chatSessionUserService.setSessionTop(USER, CONTACT, null);
            fail("置顶类型为空应拒绝");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void setSessionTop_invalidValue_throws1001() {
        try {
            chatSessionUserService.setSessionTop(USER, CONTACT, 2);
            fail("非法置顶类型应拒绝（仅 0/1）");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void setSessionTop_success_updatesAndBroadcasts() {
        when(chatSessionUserMapper.selectByUserIdAndContactId(USER, CONTACT))
                .thenReturn(sessionUserOf(SESSION_ID));

        chatSessionUserService.setSessionTop(USER, CONTACT, Constants.ZERO);

        // 落库：只更新 topType 字段
        ArgumentCaptor<ChatSessionUser> beanCaptor = ArgumentCaptor.forClass(ChatSessionUser.class);
        verify(chatSessionUserMapper).updateByUserIdAndContactId(
                beanCaptor.capture(), eq(USER), eq(CONTACT));
        assertEquals(Constants.ZERO, beanCaptor.getValue().getTopType());
        // 向本人其他在线设备广播同步
        verify(channelContextUtils).broadcastSessionUserSync(
                eq(USER), eq("top"), eq(SESSION_ID), eq(CONTACT), eq(Constants.ZERO));
    }

    @Test
    public void setSessionTop_sessionMissing_throws1003() {
        when(chatSessionUserMapper.selectByUserIdAndContactId(USER, CONTACT))
                .thenReturn(null);
        try {
            chatSessionUserService.setSessionTop(USER, CONTACT, Constants.ONE);
            fail("会话用户记录不存在应抛 1003");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1003.getCode(), e.getCode());
        }
    }

    // ======================== setSessionNoDisturb ========================

    @Test
    public void setSessionNoDisturb_invalid_throws1001() {
        try {
            chatSessionUserService.setSessionNoDisturb(USER, CONTACT, 9);
            fail("非法免打扰类型应拒绝（仅 0/1）");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void setSessionNoDisturb_success_updatesAndBroadcasts() {
        when(chatSessionUserMapper.selectByUserIdAndContactId(USER, CONTACT))
                .thenReturn(sessionUserOf(SESSION_ID));

        chatSessionUserService.setSessionNoDisturb(USER, CONTACT, Constants.ONE);

        ArgumentCaptor<ChatSessionUser> beanCaptor = ArgumentCaptor.forClass(ChatSessionUser.class);
        verify(chatSessionUserMapper).updateByUserIdAndContactId(
                beanCaptor.capture(), eq(USER), eq(CONTACT));
        assertEquals(Constants.ONE, beanCaptor.getValue().getNoDisturb());
        verify(channelContextUtils).broadcastSessionUserSync(
                eq(USER), eq("noDisturb"), eq(SESSION_ID), eq(CONTACT), eq(Constants.ONE));
    }

    // ======================== saveSessionDraft ========================

    @Test
    public void saveSessionDraft_nullNormalizedToEmpty() {
        when(chatSessionUserMapper.selectByUserIdAndContactId(USER, CONTACT))
                .thenReturn(sessionUserOf(SESSION_ID));

        // 草稿为 null → 落库为空串（清草稿语义），不写 null
        chatSessionUserService.saveSessionDraft(USER, CONTACT, null);

        ArgumentCaptor<ChatSessionUser> beanCaptor = ArgumentCaptor.forClass(ChatSessionUser.class);
        verify(chatSessionUserMapper).updateByUserIdAndContactId(
                beanCaptor.capture(), eq(USER), eq(CONTACT));
        assertEquals("", beanCaptor.getValue().getDraft());
        verify(channelContextUtils).broadcastSessionUserSync(
                eq(USER), eq("draft"), eq(SESSION_ID), eq(CONTACT), eq(""));
    }

    // ======================== updateRedundanceInfo ========================

    @Test
    public void updateRedundanceInfo_emptyName_noop() {
        // 昵称为空：短路，不落库不广播
        chatSessionUserService.updateRedundanceInfo("", CONTACT);
        verify(chatSessionUserMapper, never()).updateByParam(any(ChatSessionUser.class),
                any(ChatSessionUserQuery.class));
        verify(messageHandler, never()).sendMessage(any(MessageSendDto.class));
    }

    @Test
    public void updateRedundanceInfo_group_updatesAllSessionsAndBroadcastsOneFrame() {
        // 群维度：更新该群全部会话记录的 contactName，并向群发一帧 CONTACT_NAME_UPDATE
        chatSessionUserService.updateRedundanceInfo(NEW_NAME, GROUP_ID);

        ArgumentCaptor<ChatSessionUser> beanCaptor = ArgumentCaptor.forClass(ChatSessionUser.class);
        ArgumentCaptor<ChatSessionUserQuery> queryCaptor = ArgumentCaptor.forClass(ChatSessionUserQuery.class);
        verify(chatSessionUserMapper).updateByParam(beanCaptor.capture(),
                queryCaptor.capture());
        assertEquals(NEW_NAME, beanCaptor.getValue().getContactName());
        assertEquals(GROUP_ID, queryCaptor.getValue().getContactId());

        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(messageHandler).sendMessage(dtoCaptor.capture());
        MessageSendDto sent = dtoCaptor.getValue();
        assertEquals(MessageTypeEnum.CONTACT_NAME_UPDATE.getType(), sent.getMessageType());
        // 群通道：contactType=群、contactId=群ID、扩展数据为新昵称
        assertEquals(UserContactTypeEnum.GROUP.getType(), sent.getContactType());
        assertEquals(GROUP_ID, sent.getContactId());
        assertEquals(NEW_NAME, sent.getExtendData());
        // 群通道不走好友逐条广播
        verify(userContactMapper, never()).selectList(any(UserContactQuery.class));
    }

    @Test
    public void updateRedundanceInfo_user_broadcastsToEachFriend() {
        // 好友维度：更新会话记录后，向每个好友各发一帧 CONTACT_NAME_UPDATE
        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Arrays.asList(friendOf("U_friend1"), friendOf("U_friend2")));

        chatSessionUserService.updateRedundanceInfo(NEW_NAME, USER);

        verify(chatSessionUserMapper).updateByParam(any(ChatSessionUser.class),
                any(ChatSessionUserQuery.class));
        // 好友列表查询条件：用户维度 + 目标联系人 + 好友状态
        ArgumentCaptor<UserContactQuery> queryCaptor = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactMapper).selectList(queryCaptor.capture());
        UserContactQuery query = queryCaptor.getValue();
        assertEquals(UserContactTypeEnum.USER.getType(), query.getContactType());
        assertEquals(USER, query.getContactId());
        assertEquals(UserContactStatusEnum.FRIEND.getStatus(), query.getStatus());

        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(messageHandler, org.mockito.Mockito.times(2)).sendMessage(dtoCaptor.capture());
        for (MessageSendDto sent : dtoCaptor.getAllValues()) {
            assertEquals(MessageTypeEnum.CONTACT_NAME_UPDATE.getType(), sent.getMessageType());
            assertEquals(UserContactTypeEnum.USER.getType(), sent.getContactType());
            // 每个好友一条：contactId=好友ID、发送者=被改名用户
            assertEquals(NEW_NAME, sent.getSendUserNickName());
            assertEquals(USER, sent.getSendUserId());
            assertEquals(NEW_NAME, sent.getExtendData());
        }
        assertEquals("U_friend1", dtoCaptor.getAllValues().get(0).getContactId());
        assertEquals("U_friend2", dtoCaptor.getAllValues().get(1).getContactId());
    }

    // ======================== 分页查询 ========================

    @Test
    public void findListByPage_success_defaultPageSize15() {
        when(chatSessionUserMapper.selectCount(any(ChatSessionUserQuery.class))).thenReturn(3);
        when(chatSessionUserMapper.selectList(any(ChatSessionUserQuery.class)))
                .thenReturn(Collections.singletonList(sessionUserOf(SESSION_ID)));

        ChatSessionUserQuery query = new ChatSessionUserQuery();
        PaginationResultVO<ChatSessionUser> result = chatSessionUserService.findListByPage(query);

        assertEquals(Integer.valueOf(3), result.getTotalCount());
        assertEquals(Integer.valueOf(PageSize.SIZE15.getSize()), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertNotNull(query.getSimplePage());
        assertEquals(1, result.getList().size());
    }
}
