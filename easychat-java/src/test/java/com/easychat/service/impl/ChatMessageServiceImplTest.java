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
import com.easychat.mappers.ChatMessageVoiceReadMapper;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.entity.po.ChatMessageVoiceRead;
import com.easychat.redis.RedisComponet;
import com.easychat.service.GroupInfoService;
import com.easychat.service.OperationLogService;
import com.easychat.service.SensitiveWordService;
import com.easychat.websocket.ChannelContextUtils;
import com.easychat.websocket.MessageHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private ChatMessageVoiceReadMapper chatMessageVoiceReadMapper;

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

    // ======================== @所有人 服务端鉴权（2026-10-03） ========================
    //
    // 背景：@所有人 权限此前**仅在客户端生效**（Java 侧 atAll 零命中），
    // 普通成员手工构造 extraData={"atAll":true} 即可冒用群主/管理员身份，
    // openspec/specs/at-all/spec.md 自己把这条记为「已知边界」。
    // openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth

    private ChatMessage groupMessageWithAtAll(boolean atAll) {
        ChatMessage m = new ChatMessage();
        m.setContactId("G99999999999");
        m.setMessageType(MessageTypeEnum.CHAT.getType());
        m.setMessageContent("大家好");
        m.setExtraData(atAll ? "{\"atAll\":true}" : null);
        return m;
    }

    @Test
    public void saveMessage_atAll_byPlainMember_throws2305() {
        // 核心回归：普通成员发 @所有人 必须被服务端拒绝
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");
        tokenUserInfo.setNickName("成员");

        when(redisComponet.getUserContactList("U12345678901")).thenReturn(Arrays.asList("G99999999999"));
        doThrow(new BusinessException(ResponseCodeEnum.CODE_2305))
                .when(groupInfoService).checkGroupRole(eq("U12345678901"), eq("G99999999999"), any());

        try {
            chatMessageService.saveMessage(groupMessageWithAtAll(true), tokenUserInfo);
            fail("普通成员发 @所有人 应被拒绝");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_2305.getCode(), e.getCode());
        }
    }

    @Test
    public void saveMessage_atAll_byAdmin_passes() {
        // 群主/管理员放行
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");
        tokenUserInfo.setNickName("管理员");

        when(redisComponet.getUserContactList("U12345678901")).thenReturn(Arrays.asList("G99999999999"));
        UserContact admin = new UserContact();
        admin.setRole(1); // ADMIN
        when(groupInfoService.checkGroupRole(eq("U12345678901"), eq("G99999999999"), any())).thenReturn(admin);

        chatMessageService.saveMessage(groupMessageWithAtAll(true), tokenUserInfo);

        verify(groupInfoService, atLeastOnce()).checkGroupRole(eq("U12345678901"), eq("G99999999999"), any());
    }

    @Test
    public void saveMessage_withoutAtAll_doesNotCheckRole() {
        // 普通群消息**不得**触发角色校验：否则每条群消息都多一次查库，
        // 且新人刚入群、user_contact 尚未落库时会被误判成 2304 而发不出消息。
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");
        tokenUserInfo.setNickName("成员");

        when(redisComponet.getUserContactList("U12345678901")).thenReturn(Arrays.asList("G99999999999"));

        chatMessageService.saveMessage(groupMessageWithAtAll(false), tokenUserInfo);

        verify(groupInfoService, never()).checkGroupRole(anyString(), anyString(), any());
    }

    @Test
    public void saveMessage_singleChat_withAtAll_doesNotCheckRole() {
        // 单聊带 atAll=true 不应触发群角色校验：@所有人 只对群聊有意义。
        // 若按群去查角色会直接抛 2304，把**所有正常单聊**全部打挂。
        TokenUserInfoDto tokenUserInfo = new TokenUserInfoDto();
        tokenUserInfo.setUserId("U12345678901");
        tokenUserInfo.setNickName("我");

        ChatMessage single = new ChatMessage();
        single.setContactId("U88888888888");
        single.setMessageType(MessageTypeEnum.CHAT.getType());
        single.setMessageContent("hi");
        single.setExtraData("{\"atAll\":true}");

        when(redisComponet.getUserContactList("U12345678901")).thenReturn(Arrays.asList("U88888888888"));

        chatMessageService.saveMessage(single, tokenUserInfo);

        verify(groupInfoService, never()).checkGroupRole(anyString(), anyString(), any());
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

    // ==================== 语音未播放红点（2026-10-03）===================
    // 覆盖 openspec/changes/2026-10-03-location-and-voice-message：
    // markVoiceRead / loadVoiceRead。
    //
    // 核心不变式：**播放状态是 per-receiver 的**（ADR-001），
    // 因此任何操作都必须带 userId 定位，且只影响本人那一行。

    private static final String VOICE_SENDER = "U_sender";
    private static final String VOICE_RECEIVER = "U_receiver";
    private static final Long VOICE_MSG_ID = 9001L;

    private ChatMessage voiceMessage(String senderId) {
        ChatMessage m = new ChatMessage();
        m.setMessageId(VOICE_MSG_ID);
        m.setMessageType(MessageTypeEnum.VOICE.getType());
        m.setSendUserId(senderId);
        m.setSessionId("session-voice");
        m.setContactId(VOICE_RECEIVER);
        m.setContactType(UserContactTypeEnum.USER.getType());
        m.setFileName("voice_1.webm");
        m.setDuration(3);
        m.setDeleteFlag(0L);
        return m;
    }

    /** 群聊语音的 groupId（测试与冒烟共用） */
    private static final String VOICE_GROUP_ID = "G_voice_group";

    @Test
    public void markVoiceRead_receiverMarks_success() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(voiceMessage(VOICE_SENDER));

        chatMessageService.markVoiceRead(VOICE_RECEIVER, VOICE_MSG_ID);

        verify(chatMessageVoiceReadMapper).insertOrUpdate(any(ChatMessageVoiceRead.class));
        }

    /**
     * ★ 不变式：写入的 userId 必须是**调用者本人**，不能被入参之外的东西覆盖。
     * 若实现里误用 message.getSendUserId()，红点会记到发送者名下 —— 对端永远看不到红点。
     */
    @Test
    public void markVoiceRead_writesCurrentUserNotSender() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(voiceMessage(VOICE_SENDER));
        ArgumentCaptor<ChatMessageVoiceRead> captor =
                ArgumentCaptor.forClass(ChatMessageVoiceRead.class);

        chatMessageService.markVoiceRead(VOICE_RECEIVER, VOICE_MSG_ID);

        verify(chatMessageVoiceReadMapper).insertOrUpdate(captor.capture());
        assertEquals("红点必须记在播放者（当前用户）名下", VOICE_RECEIVER, captor.getValue().getUserId());
        assertEquals(VOICE_MSG_ID, captor.getValue().getMessageId());
        assertEquals(Integer.valueOf(1), captor.getValue().getIsRead());
        assertNotNull("播放时间必须写入", captor.getValue().getReadTime());
    }

    /** 发送方本人不能给自己的语音标已读（否则自己就看不到红点） */
    @Test(expected = BusinessException.class)
    public void markVoiceRead_senderCannotMark() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(voiceMessage(VOICE_SENDER));

        chatMessageService.markVoiceRead(VOICE_SENDER, VOICE_MSG_ID);
    }

    /** 无关第三方不能替他人标记 */
    @Test(expected = BusinessException.class)
    public void markVoiceRead_strangerRejected() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(voiceMessage(VOICE_SENDER));

        chatMessageService.markVoiceRead("U_stranger", VOICE_MSG_ID);
    }

    /** 消息不存在 → CODE_2201 */
    @Test
    public void markVoiceRead_messageNotFound() {
        when(chatMessageMapper.selectByMessageId(404L)).thenReturn(null);
        try {
            chatMessageService.markVoiceRead(VOICE_RECEIVER, 404L);
            fail("消息不存在应抛异常");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(2201), e.getCode());
        }
    }

    /** 非语音消息不允许标已读（防止拿红点表当通用标记表滥用） */
    @Test(expected = BusinessException.class)
    public void markVoiceRead_notVoiceMessageRejected() {
        ChatMessage text = voiceMessage(VOICE_SENDER);
        text.setMessageType(MessageTypeEnum.CHAT.getType());
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(text);

        chatMessageService.markVoiceRead(VOICE_RECEIVER, VOICE_MSG_ID);
    }

    /** 已删除的消息不允许标已读 */
    @Test(expected = BusinessException.class)
    public void markVoiceRead_deletedMessageRejected() {
        ChatMessage deleted = voiceMessage(VOICE_SENDER);
        deleted.setDeleteFlag(System.currentTimeMillis());
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(deleted);

        chatMessageService.markVoiceRead(VOICE_RECEIVER, VOICE_MSG_ID);
    }

    @Test
    public void loadVoiceRead_success() {
        when(chatMessageVoiceReadMapper.selectReadMessageIds(any(ChatMessageVoiceReadQuery.class)))
                .thenReturn(Arrays.asList(9001L, 9002L));

        List<Long> result = chatMessageService.loadVoiceRead(VOICE_RECEIVER,
                Arrays.asList(9001L, 9002L, 9003L));

        assertEquals(2, result.size());
        assertTrue(result.contains(9001L));
    }

    /**
     * ★ 不变式：查询必须**带 userId**。
     * 若漏了 userId，会返回所有人在这些消息上的已读状态 —— 泄露他人隐私。
     */
    @Test
    public void loadVoiceRead_scopedToCurrentUser() {
        ArgumentCaptor<ChatMessageVoiceReadQuery> captor =
                ArgumentCaptor.forClass(ChatMessageVoiceReadQuery.class);
        when(chatMessageVoiceReadMapper.selectReadMessageIds(any(ChatMessageVoiceReadQuery.class)))
                .thenReturn(Collections.emptyList());

        chatMessageService.loadVoiceRead(VOICE_RECEIVER, Arrays.asList(9001L));

        verify(chatMessageVoiceReadMapper).selectReadMessageIds(captor.capture());
        assertEquals("查询必须限定当前用户", VOICE_RECEIVER, captor.getValue().getUserId());
        assertEquals(1, captor.getValue().getMessageIdList().length);
        assertEquals(Long.valueOf(9001L), captor.getValue().getMessageIdList()[0]);
    }

    /** 空列表直接返回，不查库 */
    // ---------- 群聊分支：isVoiceReadReceiver 的 GROUP 路径 ----------
    // 上一轮 retro 记录「群聊分支漏测」，本轮补齐（见 engineering/retro/2026-10-03 §四）

    private ChatMessage groupVoiceMessage() {
        ChatMessage m = new ChatMessage();
        m.setMessageId(VOICE_MSG_ID);
        m.setMessageType(MessageTypeEnum.VOICE.getType());
        m.setSendUserId(VOICE_SENDER);
        m.setSessionId("session-group-voice");
        m.setContactId(VOICE_GROUP_ID);
        m.setContactType(UserContactTypeEnum.GROUP.getType());
        m.setFileName("voice_g.webm");
        m.setDuration(5);
        m.setDeleteFlag(0L);
        return m;
    }

    /** 群成员可标记该群语音已读 */
    @Test
    public void markVoiceRead_groupMemberAllowed() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(groupVoiceMessage());
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(1);

        chatMessageService.markVoiceRead(VOICE_RECEIVER, VOICE_MSG_ID);

        ArgumentCaptor<ChatMessageVoiceRead> captor =
                ArgumentCaptor.forClass(ChatMessageVoiceRead.class);
        verify(chatMessageVoiceReadMapper).insertOrUpdate(captor.capture());
        assertEquals(VOICE_RECEIVER, captor.getValue().getUserId());
        // 必须是按「我 + 该群」查群成员，而不是按 contactId 等值
        ArgumentCaptor<UserContactQuery> q = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactMapper).selectCount(q.capture());
        assertEquals(VOICE_RECEIVER, q.getValue().getUserId());
        assertEquals(VOICE_GROUP_ID, q.getValue().getContactId());
        assertEquals(UserContactTypeEnum.GROUP.getType(), q.getValue().getContactType());
    }

    /** 非群成员（含单聊对方）不能标记群语音已读 */
    @Test(expected = BusinessException.class)
    public void markVoiceRead_notGroupMemberRejected() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(groupVoiceMessage());
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(0);

        chatMessageService.markVoiceRead("U_stranger", VOICE_MSG_ID);
    }

    /** 群成员数为 null（异常/竞态）时按「非成员」处理，不得放行 */
    @Test(expected = BusinessException.class)
    public void markVoiceRead_nullGroupMemberCountRejected() {
        when(chatMessageMapper.selectByMessageId(VOICE_MSG_ID)).thenReturn(groupVoiceMessage());
        when(userContactMapper.selectCount(any(UserContactQuery.class))).thenReturn(null);

        chatMessageService.markVoiceRead(VOICE_RECEIVER, VOICE_MSG_ID);
    }

    @Test
    public void loadVoiceRead_emptyListReturnsEmpty() {
        assertTrue(chatMessageService.loadVoiceRead(VOICE_RECEIVER, Collections.emptyList()).isEmpty());
        verify(chatMessageVoiceReadMapper, never()).selectReadMessageIds(any());
    }

    /** 超过 200 个 id → CODE_1001（防滥用批量拉取） */
    @Test
    public void loadVoiceRead_tooManyIdsRejected() {
        List<Long> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(9000L + i);
        }
        try {
            chatMessageService.loadVoiceRead(VOICE_RECEIVER, tooMany);
            fail("超过 200 个 id 应抛 CODE_1001");
        } catch (BusinessException e) {
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
        verify(chatMessageVoiceReadMapper, never()).selectReadMessageIds(any());
    }
}
