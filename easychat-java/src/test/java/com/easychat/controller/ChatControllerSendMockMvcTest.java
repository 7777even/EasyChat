package com.easychat.controller;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.ChatMessage;
import com.easychat.mappers.ChatSessionUserMapper;
import com.easychat.redis.RedisUtils;
import com.easychat.service.ChatMessageService;
import com.easychat.service.ChatSessionUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ChatController} 发送链路的**行为**测试（MockMvc 真实路由）。
 *
 * <p>为什么优先覆盖这里：2026-10-03 接通位置/语音消息时，真实事故是
 * 「发送白名单只有 {2,5} → 点发送位置/按住说话直接 CODE_1001、连消息都建不出来」，
 * 且五层全断、无一抛异常。发送白名单与参数守卫是**最容易被后续改动悄悄收窄**的地方，
 * 而它一旦收窄，症状是「用户点了没反应」而非报错。
 *
 * <p>被测点：
 * <ol>
 *   <li><b>发送白名单恰为 {2 普通聊天, 5 媒体消息, 24 语音, 25 位置}</b>：
 *       白名单内的放行，枚举存在但不在白名单的（1/14/20/26 等）与完全未知的（99）一律 1001，
 *       且<b>不得</b>调用 saveMessage</li>
 *   <li>语音守卫：缺文件名 / 缺时长 / 时长 &lt;1 / 超 60 秒 / fileType≠3</li>
 *   <li>位置守卫：缺 extraData / 非法 JSON / JSON 无 location / 超 2000 字符</li>
 *   <li>字段映射：10 个入参全量落到 {@link ChatMessage}（漏一个即「消息发出但内容丢失」）</li>
 *   <li>身份取自 token 会话，伪造 userId 参数被忽略</li>
 *   <li>语音已播放：批量 id 解析与非法元素拦截</li>
 * </ol>
 */
class ChatControllerSendMockMvcTest {

    private static final String TOKEN = "tok-abc";
    private static final String USER_ID = "U001";

    /** 发送白名单（与 MessageTypeEnum 数值对应） */
    private static final int TYPE_CHAT = 2;
    private static final int TYPE_MEDIA = 5;
    private static final int TYPE_VOICE = 24;
    private static final int TYPE_LOCATION = 25;

    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private ChatSessionUserService chatSessionUserService;
    @Mock
    private ChatSessionUserMapper chatSessionUserMapper;
    @Mock
    private AppConfig appConfig;
    /**
     * {@code ABaseController} 的会话缓存依赖。它是<b>基类私有字段</b>，
     * {@code @InjectMocks} 会注入到继承字段上——不声明此处 mock 会得到 null，
     * 进而在 getTokenUserInfo 处 NPE（表现为 500，极易误判成业务缺陷）。
     */
    @Mock
    private RedisUtils redisUtils;

    @InjectMocks
    private ChatController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
        TokenUserInfoDto session = new TokenUserInfoDto();
        session.setToken(TOKEN);
        session.setUserId(USER_ID);
        session.setNickName("小明");
        when(redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + TOKEN)).thenReturn(session);
        when(chatMessageService.saveMessage(any(), any())).thenReturn(new MessageSendDto());
    }

    // ==================== 白名单 ====================

    @Test
    @DisplayName("白名单放行：普通聊天(2) → 调用 saveMessage")
    void plainChatIsAllowed() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "在吗")
                        .param("messageType", String.valueOf(TYPE_CHAT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(chatMessageService).saveMessage(any(), any());
    }

    @Test
    @DisplayName("白名单放行：媒体消息(5)")
    void mediaChatIsAllowed() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_MEDIA))
                        .param("fileType", "1"))
                .andExpect(status().isOk());
        verify(chatMessageService).saveMessage(any(), any());
    }

    @Test
    @DisplayName("白名单放行：语音(24)，守卫齐全时正常发送")
    void voiceWithAllGuardsPasses() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_VOICE))
                        .param("fileName", "a.webm")
                        .param("duration", "5")
                        .param("fileType", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(chatMessageService).saveMessage(any(), any());
    }

    @Test
    @DisplayName("白名单放行：位置(25)，extraData 含 location")
    void locationWithValidExtraDataPasses() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_LOCATION))
                        .param("extraData", "{\"location\":\"北京市东城区\",\"longitude\":116.4,\"latitude\":39.9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(chatMessageService).saveMessage(any(), any());
    }

    @Test
    @DisplayName("枚举存在但不在白名单的类型（引用14/管理员删除20/拍一拍26/好友1）→ 1001 且不落库")
    void nonWhitelistedExistingTypesRejected() throws Exception {
        for (int type : new int[]{1, 14, 20, 26}) {
            mockMvc.perform(post("/chat/sendMessage")
                            .header("token", TOKEN)
                            .param("contactId", "U010")
                            .param("messageContent", "x")
                            .param("messageType", String.valueOf(type)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1001));
        }
        verify(chatMessageService, never()).saveMessage(any(), any());
    }

    @Test
    @DisplayName("完全未知的类型(99) → 1001 且不落库")
    void unknownTypeRejected() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "x")
                        .param("messageType", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verify(chatMessageService, never()).saveMessage(any(), any());
    }

    // ==================== 语音守卫 ====================

    @Test
    @DisplayName("语音缺文件名 → 1001「语音消息缺少文件名」")
    void voiceRequiresFileName() throws Exception {
        assertVoiceRejected("", "5", "3", "语音消息缺少文件名");
    }

    @Test
    @DisplayName("语音缺时长 → 1001「语音消息缺少时长」")
    void voiceRequiresDuration() throws Exception {
        assertVoiceRejected("a.webm", null, "3", "语音消息缺少时长");
        assertVoiceRejected("a.webm", "0", "3", "语音消息缺少时长");
    }

    @Test
    @DisplayName("语音时长超上限(>60) → 1001 且文案点明上限（防伪造参数绕过前端限制）")
    void voiceDurationUpperBoundEnforced() throws Exception {
        assertVoiceRejected("a.webm", "61", "3", "语音时长不能超过 60 秒");
    }

    @Test
    @DisplayName("语音 fileType 必须为 3 → 否则接收方无法走音频渲染分支")
    void voiceRequiresFileType3() throws Exception {
        assertVoiceRejected("a.webm", "5", "1", "语音消息 fileType 必须为 3");
    }

    @Test
    @DisplayName("语音时长恰好 60 秒（边界值）→ 放行")
    void voiceDurationBoundaryInclusive() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_VOICE))
                        .param("fileName", "a.webm")
                        .param("duration", String.valueOf(Constants.VOICE_MAX_DURATION_SECONDS))
                        .param("fileType", "3"))
                .andExpect(status().isOk());
    }

    // ==================== 位置守卫 ====================

    @Test
    @DisplayName("位置缺 extraData → 1001「位置消息缺少位置信息」")
    void locationRequiresExtraData() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_LOCATION)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("位置消息缺少位置信息"));
        verify(chatMessageService, never()).saveMessage(any(), any());
    }

    @Test
    @DisplayName("位置 extraData 非法 JSON → 1001「位置信息格式错误」")
    void locationRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_LOCATION))
                        .param("extraData", "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("位置信息格式错误"));
    }

    @Test
    @DisplayName("位置 extraData 为合法 JSON 但无 location 字段 → 1001（不得把无位置当成合法）")
    void locationRequiresLocationField() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_LOCATION))
                        .param("extraData", "{\"address\":\"某地\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("位置信息缺少 location 字段"));
    }

    @Test
    @DisplayName("位置 extraData 超 2000 字符 → 1001「位置信息超长」")
    void locationRejectsOversizeExtraData() throws Exception {
        StringBuilder sb = new StringBuilder("{\"location\":\"");
        for (int i = 0; i < 2100; i++) {
            sb.append('x');
        }
        sb.append("\"}");
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_LOCATION))
                        .param("extraData", sb.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("位置信息超长"));
        verify(chatMessageService, never()).saveMessage(any(), any());
    }

    // ==================== 字段映射 ====================

    @Test
    @DisplayName("字段映射：10 个入参全量落到 ChatMessage（漏一个即消息内容丢失）")
    void allParametersAreMappedToEntity() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "看这里")
                        .param("messageType", String.valueOf(TYPE_CHAT))
                        .param("fileSize", "2048")
                        .param("fileName", "a.png")
                        .param("fileType", "0")
                        .param("clientId", "cli-1")
                        .param("extraData", "{\"k\":\"v\"}")
                        .param("atUserIds", "[\"U011\"]")
                        .param("duration", "7"))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageService).saveMessage(captor.capture(), any());
        ChatMessage m = captor.getValue();
        assertEquals("U010", m.getContactId());
        assertEquals("看这里", m.getMessageContent());
        assertEquals(Integer.valueOf(TYPE_CHAT), m.getMessageType());
        assertEquals(Long.valueOf(2048L), m.getFileSize());
        assertEquals("a.png", m.getFileName());
        assertEquals(Integer.valueOf(0), m.getFileType());
        assertEquals("cli-1", m.getClientId());
        assertEquals("{\"k\":\"v\"}", m.getExtraData());
        assertEquals("[\"U011\"]", m.getAtUserIds());
        assertEquals(Integer.valueOf(7), m.getDuration());
    }

    @Test
    @DisplayName("可选参数缺省时落 null，不得被填成 0 或空串（0 会让 fileSize 变成「0 字节」）")
    void optionalParametersStayNullWhenAbsent() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "在吗")
                        .param("messageType", String.valueOf(TYPE_CHAT)))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageService).saveMessage(captor.capture(), any());
        ChatMessage m = captor.getValue();
        assertNull(m.getFileSize());
        assertNull(m.getFileName());
        assertNull(m.getFileType());
        assertNull(m.getDuration());
        assertNull(m.getExtraData());
        assertNull(m.getAtUserIds());
    }

    @Test
    @DisplayName("身份取自 token 会话，伪造的 userId 参数被忽略")
    void identityComesFromSession() throws Exception {
        mockMvc.perform(post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("userId", "U999")
                        .param("contactId", "U010")
                        .param("messageContent", "在吗")
                        .param("messageType", String.valueOf(TYPE_CHAT)))
                .andExpect(status().isOk());

        ArgumentCaptor<TokenUserInfoDto> captor = ArgumentCaptor.forClass(TokenUserInfoDto.class);
        verify(chatMessageService).saveMessage(any(), captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
    }

    // ==================== 语音已播放 ====================

    @Test
    @DisplayName("markVoiceRead：userId 取自会话")
    void markVoiceReadUsesSessionIdentity() throws Exception {
        mockMvc.perform(post("/chat/markVoiceRead")
                        .header("token", TOKEN)
                        .param("messageId", "888"))
                .andExpect(status().isOk());
        verify(chatMessageService).markVoiceRead(USER_ID, 888L);
    }

    @Test
    @DisplayName("loadVoiceRead：逗号列表解析（含空白与空项），已播 id 原样返回")
    void loadVoiceReadParsesList() throws Exception {
        when(chatMessageService.loadVoiceRead(anyString(), anyList())).thenReturn(Arrays.asList(1L, 3L));

        mockMvc.perform(post("/chat/loadVoiceRead")
                        .header("token", TOKEN)
                        .param("messageIdList", "1, 2 ,,3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));

        verify(chatMessageService).loadVoiceRead(USER_ID, Arrays.asList(1L, 2L, 3L));
    }

    @Test
    @DisplayName("loadVoiceRead：非数字元素 → 1001 且点名该元素（不得静默丢弃）")
    void loadVoiceReadRejectsNonNumeric() throws Exception {
        mockMvc.perform(post("/chat/loadVoiceRead")
                        .header("token", TOKEN)
                        .param("messageIdList", "1,abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("messageIdList 存在非数字元素: abc"));
        verify(chatMessageService, never()).loadVoiceRead(anyString(), anyList());
    }

    @Test
    @DisplayName("loadVoiceRead：空结果返回空数组而非 null（前端遍历不得 NPE）")
    void loadVoiceReadEmptyListIsSuccess() throws Exception {
        when(chatMessageService.loadVoiceRead(anyString(), anyList())).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/chat/loadVoiceRead")
                        .header("token", TOKEN)
                        .param("messageIdList", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ==================== 辅助 ====================

    private void assertVoiceRejected(String fileName, String duration, String fileType, String expectedMessage)
            throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                post("/chat/sendMessage")
                        .header("token", TOKEN)
                        .param("contactId", "U010")
                        .param("messageContent", "")
                        .param("messageType", String.valueOf(TYPE_VOICE))
                        .param("fileType", fileType);
        if (fileName != null) {
            builder.param("fileName", fileName);
        }
        if (duration != null) {
            builder.param("duration", duration);
        }
        mockMvc.perform(builder)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verify(chatMessageService, never()).saveMessage(any(), any());
    }
}
