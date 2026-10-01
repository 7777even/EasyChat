package com.easychat.service.impl;

import com.easychat.config.EasyChatProperties;
import com.easychat.entity.config.AppConfig;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.*;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.ChatSession;
import com.easychat.entity.po.ChatSessionUser;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.*;
import com.easychat.entity.vo.GlobalSearchResultVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.GroupInfoService;
import com.easychat.service.OperationLogService;
import com.easychat.service.SensitiveWordService;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatMessageServiceImpl 单元测试
 */
@RunWith(MockitoJUnitRunner.class)
public class ChatMessageServiceImplTest {

    @InjectMocks
    private ChatMessageServiceImpl chatMessageService;

    @Mock
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Mock
    private SensitiveWordService sensitiveWordService;

    @Mock
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Mock
    private MessageHandler messageHandler;

    @Mock
    private AppConfig appConfig;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private ChannelContextUtils channelContextUtils;

    @Mock
    private GroupInfoService groupInfoService;

    @Mock
    private EasyChatProperties easyChatProperties;

    @Mock
    private ChatSessionUserMapper<ChatSessionUser, ChatSessionUserQuery> chatSessionUserMapper;

    @Mock
    private OperationLogService operationLogService;

    // ======================== 保存消息 ========================

    @Test
    public void saveMessage_textMessage_success() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageType(MessageTypeEnum.CHAT.getType());
        chatMessage.setMessageContent("你好");
        chatMessage.setContactId("U99999999999");
        chatMessage.setClientId("client-001");

        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");
        tokenUserInfo.setNickName("测试用户");

        List<String> contactList = Arrays.asList("U99999999999");
        when(redisComponet.getUserContactList("U12345678901")).thenReturn(contactList);
        when(sensitiveWordService.filter("你好")).thenReturn("你好");
        when(chatSessionMapper.updateBySessionId(any(ChatSession.class), anyString())).thenReturn(1);
        when(redisComponet.nextMessageSeq(anyString())).thenReturn(1L);
        when(chatMessageMapper.insert(any(ChatMessage.class))).thenReturn(1);

        MessageSendDto result = chatMessageService.saveMessage(chatMessage, tokenUserInfo);

        assertNotNull(result);
        verify(chatMessageMapper).insert(any(ChatMessage.class));
        verify(messageHandler).sendMessage(any());
    }

    @Test(expected = BusinessException.class)
    public void saveMessage_notFriend() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageType(MessageTypeEnum.CHAT.getType());
        chatMessage.setMessageContent("你好");
        chatMessage.setContactId("U99999999999");

        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");

        when(redisComponet.getUserContactList("U12345678901")).thenReturn(new ArrayList<>());

        chatMessageService.saveMessage(chatMessage, tokenUserInfo);
    }

    @Test(expected = BusinessException.class)
    public void saveMessage_notInGroup() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageType(MessageTypeEnum.CHAT.getType());
        chatMessage.setMessageContent("你好");
        chatMessage.setContactId("G99999999999");

        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");

        when(redisComponet.getUserContactList("U12345678901")).thenReturn(new ArrayList<>());

        chatMessageService.saveMessage(chatMessage, tokenUserInfo);
    }

    @Test
    public void saveMessage_groupMessage_muted() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageType(MessageTypeEnum.CHAT.getType());
        chatMessage.setMessageContent("你好");
        chatMessage.setContactId("G99999999999");

        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");

        List<String> contactList = Arrays.asList("G99999999999");
        when(redisComponet.getUserContactList("U12345678901")).thenReturn(contactList);
        doThrow(new BusinessException(ResponseCodeEnum.CODE_2307))
                .when(groupInfoService).checkMuted("U12345678901", "G99999999999");

        try {
            chatMessageService.saveMessage(chatMessage, tokenUserInfo);
            fail("应该抛出禁言异常");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2307.getCode(), e.getCode());
        }
    }

    // ======================== 撤回消息 ========================

    @Test
    public void recallMessage_success() {
        Long messageId = 1L;
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");
        tokenUserInfo.setNickName("测试用户");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setSendUserId("U12345678901");
        message.setMessageType(MessageTypeEnum.CHAT.getType());
        message.setSendTime(System.currentTimeMillis() - 60000); // 1分钟前
        message.setSessionId("session-001");
        message.setContactId("U99999999999");
        message.setContactType(UserContactTypeEnum.USER.getType());
        message.setDeleteFlag(0L);

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);
        when(chatMessageMapper.updateByMessageId(any(ChatMessage.class), eq(messageId))).thenReturn(1);

        MessageSendDto result = chatMessageService.recallMessage(messageId, tokenUserInfo);

        assertNotNull(result);
        assertEquals(MessageTypeEnum.RECALL_MESSAGE.getType(), result.getMessageType());
        verify(messageHandler).sendMessage(any());
    }

    @Test(expected = BusinessException.class)
    public void recallMessage_notSender() {
        Long messageId = 1L;
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U00000000000");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setSendUserId("U12345678901");
        message.setMessageType(MessageTypeEnum.CHAT.getType());
        message.setSendTime(System.currentTimeMillis() - 60000);
        message.setDeleteFlag(0L);

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);

        chatMessageService.recallMessage(messageId, tokenUserInfo);
    }

    @Test(expected = BusinessException.class)
    public void recallMessage_timeout() {
        Long messageId = 1L;
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setSendUserId("U12345678901");
        message.setMessageType(MessageTypeEnum.CHAT.getType());
        message.setSendTime(System.currentTimeMillis() - 180000); // 3分钟前
        message.setDeleteFlag(0L);

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);

        chatMessageService.recallMessage(messageId, tokenUserInfo);
    }

    @Test(expected = BusinessException.class)
    public void recallMessage_messageNotFound() {
        Long messageId = 999L;
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(null);

        chatMessageService.recallMessage(messageId, tokenUserInfo);
    }

    @Test(expected = BusinessException.class)
    public void recallMessage_deletedMessage() {
        Long messageId = 1L;
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setSendUserId("U12345678901");
        message.setMessageType(MessageTypeEnum.CHAT.getType());
        message.setSendTime(System.currentTimeMillis() - 60000);
        message.setDeleteFlag(System.currentTimeMillis()); // 已删除

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);

        chatMessageService.recallMessage(messageId, tokenUserInfo);
    }

    // ======================== 管理端删除消息 ========================

    @Test
    public void adminDeleteMessage_success() {
        Long messageId = 1L;
        TokenUserInfoDto admin = new TokenUserInfoDto();
        admin.setUserId("U00000000000");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setSendUserId("U12345678901");
        message.setSendUserNickName("测试用户");
        message.setSendTime(System.currentTimeMillis() - 60000);
        message.setSessionId("session-001");
        message.setContactId("U99999999999");
        message.setContactType(UserContactTypeEnum.USER.getType());
        message.setDeleteFlag(0L);

        ChatSession chatSession = new ChatSession();
        chatSession.setSessionId("session-001");
        chatSession.setLastReceiveTime(System.currentTimeMillis() - 120000);

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);
        when(chatMessageMapper.updateByMessageId(any(ChatMessage.class), eq(messageId))).thenReturn(1);
        when(chatSessionMapper.selectBySessionId("session-001")).thenReturn(chatSession);
        when(chatSessionMapper.updateBySessionId(any(ChatSession.class), eq("session-001"))).thenReturn(1);

        boolean result = chatMessageService.adminDeleteMessage(messageId, admin);

        assertTrue(result);
        verify(messageHandler).sendMessage(any());
    }

    @Test
    public void adminDeleteMessage_alreadyDeleted() {
        Long messageId = 1L;
        TokenUserInfoDto admin = new TokenUserInfoDto();
        admin.setUserId("U00000000000");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setDeleteFlag(System.currentTimeMillis()); // 已删除

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);

        boolean result = chatMessageService.adminDeleteMessage(messageId, admin);

        assertFalse(result);
        verify(messageHandler, never()).sendMessage(any());
    }

    @Test
    public void adminDeleteMessage_messageNotFound() {
        Long messageId = 999L;
        TokenUserInfoDto admin = new TokenUserInfoDto();
        admin.setUserId("U00000000000");

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(null);

        boolean result = chatMessageService.adminDeleteMessage(messageId, admin);

        assertFalse(result);
    }

    // ======================== 搜索消息 ========================

    @Test
    public void searchMessage_withKeyword() {
        ChatMessageQuery query = new ChatMessageQuery();
        String keyword = "测试";

        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(5);
        when(chatMessageMapper.selectList(any(ChatMessageQuery.class))).thenReturn(new ArrayList<>());

        PaginationResultVO<ChatMessage> result = chatMessageService.searchMessage(query, keyword, null, null, null, null);

        assertNotNull(result);
        assertEquals(Integer.valueOf(5), result.getTotalCount());
    }

    @Test
    public void searchMessage_withSendUserId() {
        ChatMessageQuery query = new ChatMessageQuery();
        String sendUserId = "U12345678901";

        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(3);
        when(chatMessageMapper.selectList(any(ChatMessageQuery.class))).thenReturn(new ArrayList<>());

        PaginationResultVO<ChatMessage> result = chatMessageService.searchMessage(query, null, sendUserId, null, null, null);

        assertNotNull(result);
        assertEquals(Integer.valueOf(3), result.getTotalCount());
    }

    @Test
    public void searchMessage_withTimeRange() {
        ChatMessageQuery query = new ChatMessageQuery();
        Long startTime = System.currentTimeMillis() - 86400000L;
        Long endTime = System.currentTimeMillis();

        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(2);
        when(chatMessageMapper.selectList(any(ChatMessageQuery.class))).thenReturn(new ArrayList<>());

        PaginationResultVO<ChatMessage> result = chatMessageService.searchMessage(query, null, null, null, startTime, endTime);

        assertNotNull(result);
    }

    // ======================== 历史消息 ========================

    @Test
    public void loadHistoryMessage_success() {
        String sessionId = "session-001";
        Long lastMessageId = 100L;
        Integer pageSize = 20;

        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(50);
        when(chatMessageMapper.selectList(any(ChatMessageQuery.class))).thenReturn(new ArrayList<>());

        PaginationResultVO<ChatMessage> result = chatMessageService.loadHistoryMessage(sessionId, lastMessageId, pageSize);

        assertNotNull(result);
        assertEquals(Integer.valueOf(50), result.getTotalCount());
    }

    // ======================== 定位消息 ========================

    @Test
    public void locateMessage_success() {
        Long messageId = 50L;
        Integer pageSize = 20;

        ChatMessage target = new ChatMessage();
        target.setMessageId(messageId);
        target.setSessionId("session-001");
        target.setDeleteFlag(0L);

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(target);
        when(chatMessageMapper.selectCount(any(ChatMessageQuery.class))).thenReturn(50);
        when(chatMessageMapper.selectList(any(ChatMessageQuery.class))).thenReturn(new ArrayList<>());

        PaginationResultVO<ChatMessage> result = chatMessageService.locateMessage(messageId, pageSize);

        assertNotNull(result);
    }

    @Test(expected = BusinessException.class)
    public void locateMessage_deleted() {
        Long messageId = 50L;
        Integer pageSize = 20;

        ChatMessage target = new ChatMessage();
        target.setMessageId(messageId);
        target.setDeleteFlag(System.currentTimeMillis());

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(target);

        chatMessageService.locateMessage(messageId, pageSize);
    }

    // ======================== 全局搜索 ========================

    @Test
    public void globalSearch_emptyKeyword() {
        String userId = "U12345678901";
        String keyword = "";

        GlobalSearchResultVO result = chatMessageService.globalSearch(userId, keyword, "all");

        assertNotNull(result);
        // 空关键词时返回空结果对象，messageList 为 null（未设置）
        assertTrue(result.getMessageList() == null || result.getMessageList().isEmpty());
    }

    @Test
    public void globalSearch_messageScope() {
        String userId = "U12345678901";
        String keyword = "测试";

        ChatSessionUser sessionUser = new ChatSessionUser();
        sessionUser.setSessionId("session-001");

        when(chatSessionUserMapper.selectList(any(ChatSessionUserQuery.class)))
                .thenReturn(Collections.singletonList(sessionUser));
        when(chatMessageMapper.selectList(any(ChatMessageQuery.class))).thenReturn(new ArrayList<>());

        GlobalSearchResultVO result = chatMessageService.globalSearch(userId, keyword, "message");

        assertNotNull(result);
    }

    @Test
    public void globalSearch_contactScope() {
        String userId = "U12345678901";
        String keyword = "张";

        UserContact contact = new UserContact();
        contact.setContactId("U99999999999");
        contact.setContactName("张三");
        contact.setContactType(UserContactTypeEnum.USER.getType());
        contact.setStatus(UserContactStatusEnum.FRIEND.getStatus());

        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(contact));

        GlobalSearchResultVO result = chatMessageService.globalSearch(userId, keyword, "contact");

        assertNotNull(result);
        assertNotNull(result.getContactList());
    }

    @Test
    public void globalSearch_groupScope() {
        String userId = "U12345678901";
        String keyword = "群";

        UserContact contact = new UserContact();
        contact.setContactId("G99999999999");
        contact.setContactName("测试群");
        contact.setContactType(UserContactTypeEnum.GROUP.getType());
        contact.setStatus(UserContactStatusEnum.FRIEND.getStatus());

        when(userContactMapper.selectList(any(UserContactQuery.class)))
                .thenReturn(Collections.singletonList(contact));

        GlobalSearchResultVO result = chatMessageService.globalSearch(userId, keyword, "group");

        assertNotNull(result);
        assertNotNull(result.getGroupList());
    }

    // ======================== 查询方法 ========================

    @Test
    public void getChatMessageByMessageId_success() {
        Long messageId = 1L;
        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setMessageContent("测试消息");

        when(chatMessageMapper.selectByMessageId(messageId)).thenReturn(message);

        ChatMessage result = chatMessageService.getChatMessageByMessageId(messageId);

        assertNotNull(result);
        assertEquals(messageId, result.getMessageId());
    }

    @Test
    public void findListByParam_success() {
        ChatMessageQuery query = new ChatMessageQuery();
        List<ChatMessage> messageList = new ArrayList<>();
        ChatMessage message = new ChatMessage();
        message.setMessageId(1L);
        messageList.add(message);

        when(chatMessageMapper.selectList(query)).thenReturn(messageList);

        List<ChatMessage> result = chatMessageService.findListByParam(query);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    public void findCountByParam_success() {
        ChatMessageQuery query = new ChatMessageQuery();

        when(chatMessageMapper.selectCount(query)).thenReturn(10);

        Integer count = chatMessageService.findCountByParam(query);

        assertEquals(Integer.valueOf(10), count);
    }
}
