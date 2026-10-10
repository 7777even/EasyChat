package com.easychat.controller;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.po.UserStatus;
import com.easychat.redis.RedisUtils;
import com.easychat.service.EmojiService;
import com.easychat.service.FavoriteService;
import com.easychat.service.ReportService;
import com.easychat.service.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 四个薄委托型控制器（表情包 / 收藏 / 举报 / 在线状态）的**行为**测试。
 *
 * <p>合并在一个文件里是因为它们的风险同构，且都很薄：本身几乎无分支，
 * 但「把身份从会话换成参数」「把 GET 写成 POST」「委托参数错位」这三类改动
 * 都不会抛异常，只会静默越权或让前端拿不到数据。
 *
 * <p>覆盖：① 身份一律取自 token 会话，伪造 userId 参数被忽略；
 * ② 幂等查询用 GET、写操作用 POST；③ 委托参数逐个透传；
 * ④ 可选参数缺省为 null；⑤ 空结果返回空数组而非 null。
 */
class ThinDelegatingControllersMockMvcTest {

    private static final String TOKEN = MockMvcSupport.TOKEN;
    private static final String USER_ID = MockMvcSupport.USER_ID;

    @Mock
    private EmojiService emojiService;
    @Mock
    private FavoriteService favoriteService;
    @Mock
    private ReportService reportService;
    @Mock
    private UserStatusService userStatusService;
    @Mock
    private RedisUtils redisUtils;

    private MockMvc emojiMvc;
    private MockMvc favoriteMvc;
    private MockMvc reportMvc;
    private MockMvc statusMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TokenUserInfoDto session = MockMvcSupport.sessionUser();
        MockMvcSupport.stubSession(redisUtils, session);

        emojiMvc = build(new EmojiController());
        favoriteMvc = build(new FavoriteController());
        reportMvc = build(new ReportController());
        statusMvc = build(new UserStatusController());
    }

    /**
     * 手工装配：控制器字段是 {@code @Resource} 私有字段且继承自基类，
     * 这里用反射注入，避免每个控制器都写一遍 @InjectMocks + 六个 mock。
     */
    private MockMvc build(Object controller) {
        inject(controller, "emojiService", emojiService);
        inject(controller, "favoriteService", favoriteService);
        inject(controller, "reportService", reportService);
        inject(controller, "userStatusService", userStatusService);
        inject(controller, "redisUtils", redisUtils); // 基类 ABaseController 的私有字段
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
    }

    private static void inject(Object target, String field, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ==================== 表情包 ====================

    @Test
    @DisplayName("表情包列表：GET + 身份取自会话")
    void emojiListUsesGetAndSession() throws Exception {
        when(emojiService.listEmoji(USER_ID)).thenReturn(Collections.emptyList());

        emojiMvc.perform(get("/emoji/list").header("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
        verify(emojiService).listEmoji(USER_ID);

        // 写操作不得用 GET
        emojiMvc.perform(get("/emoji/delete").header("token", TOKEN).param("emojiId", "1"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("表情包上传：文件透传 + 伪造 userId 被忽略")
    void emojiUploadPassesFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "e.png", "image/png", new byte[]{1});

        emojiMvc.perform(multipart("/emoji/upload")
                        .file(file)
                        .header("token", TOKEN)
                        .param("userId", "U999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<String> uid = ArgumentCaptor.forClass(String.class);
        verify(emojiService).uploadEmoji(uid.capture(), any());
        assertEquals(USER_ID, uid.getValue());
    }

    @Test
    @DisplayName("表情包删除：emojiId 透传，身份取自会话")
    void emojiDeleteDelegates() throws Exception {
        emojiMvc.perform(multipart("/emoji/delete")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("userId", "U999")
                        .param("emojiId", "88"))
                .andExpect(status().isOk());
        verify(emojiService).deleteEmoji(USER_ID, 88L);
    }

    // ==================== 收藏 ====================

    @Test
    @DisplayName("新增收藏：四个参数透传（messageId/content/filePath 位置不得错）")
    void addFavoritePassesAllArguments() throws Exception {
        favoriteMvc.perform(post("/favorite/add")
                        .header("token", TOKEN)
                        .param("messageId", "555")
                        .param("content", "值得收藏")
                        .param("filePath", "D:/a.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(favoriteService).addFavorite(USER_ID, 555L, "值得收藏", "D:/a.png");
    }

    @Test
    @DisplayName("新增收藏：filePath 缺省为 null（不得被填成空串，落库会多出无意义路径）")
    void addFavoriteOptionalFilePathStaysNull() throws Exception {
        favoriteMvc.perform(post("/favorite/add")
                        .header("token", TOKEN)
                        .param("messageId", "555")
                        .param("content", "纯文本"))
                .andExpect(status().isOk());

        verify(favoriteService).addFavorite(USER_ID, 555L, "纯文本", null);
    }

    @Test
    @DisplayName("取消收藏：favoriteId 透传，身份取自会话")
    void cancelFavoriteDelegates() throws Exception {
        favoriteMvc.perform(post("/favorite/cancel").header("token", TOKEN).param("favoriteId", "77"))
                .andExpect(status().isOk());
        verify(favoriteService).cancelFavorite(USER_ID, 77L);
    }

    @Test
    @DisplayName("收藏列表：GET + 空结果返回空数组")
    void listFavoriteEmptyIsArray() throws Exception {
        when(favoriteService.listFavorite(USER_ID)).thenReturn(Collections.emptyList());

        favoriteMvc.perform(get("/favorite/list").header("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 取消是写操作，不得用 GET
        favoriteMvc.perform(get("/favorite/cancel").header("token", TOKEN).param("favoriteId", "1"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ==================== 举报 ====================

    @Test
    @DisplayName("举报动态：momentId/reason/description 透传 + 举报人取自会话")
    void reportMomentDelegates() throws Exception {
        reportMvc.perform(post("/report/moment")
                        .header("token", TOKEN)
                        .param("momentId", "901")
                        .param("reason", "1")
                        .param("description", "不良内容"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<TokenUserInfoDto> user = ArgumentCaptor.forClass(TokenUserInfoDto.class);
        verify(reportService).reportMoment(eq(901L), any(), eq(1), eq("不良内容"), user.capture());
        assertEquals(USER_ID, user.getValue().getUserId());
    }

    @Test
    @DisplayName("举报评论：commentId 与 momentId 的位置不得互换（写反即举报错对象）")
    void reportCommentKeepsArgumentOrder() throws Exception {
        reportMvc.perform(post("/report/moment")
                        .header("token", TOKEN)
                        .param("momentId", "901")
                        .param("commentId", "77"))
                .andExpect(status().isOk());

        verify(reportService).reportMoment(eq(901L), eq(77L), any(), any(), any());
    }

    @Test
    @DisplayName("举报消息：走 reportMessage（不是 reportMoment），举报人取自会话")
    void reportChatDelegates() throws Exception {
        reportMvc.perform(post("/report/chat")
                        .header("token", TOKEN)
                        .param("messageId", "555")
                        .param("reason", "2"))
                .andExpect(status().isOk());

        verify(reportService).reportMessage(eq(555L), eq(2), any(), any());
        verify(reportService, never()).reportMoment(anyLong(), any(), any(), any(), any());
    }

    // ==================== 在线状态 ====================

    @Test
    @DisplayName("设置状态：只能改自己的（userId 取自会话，伪造参数被忽略）")
    void setStatusUsesSessionIdentity() throws Exception {
        statusMvc.perform(post("/userStatus/set")
                        .header("token", TOKEN)
                        .param("userId", "U999")
                        .param("content", "开会中"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userStatusService).setStatus(USER_ID, "开会中", null);
    }

    @Test
    @DisplayName("获取状态：GET + 可查他人（传的是目标 userId，不是会话者）")
    void getStatusAllowsQueryingOthers() throws Exception {
        UserStatus status = new UserStatus();
        status.setUserId("U010");
        status.setContent("吃饭中");
        when(userStatusService.getStatus("U010")).thenReturn(status);

        statusMvc.perform(get("/userStatus/get").header("token", TOKEN).param("userId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("吃饭中"));
        verify(userStatusService).getStatus("U010");
    }

    @Test
    @DisplayName("清除状态：只清自己的")
    void clearStatusUsesSessionIdentity() throws Exception {
        statusMvc.perform(post("/userStatus/clear").header("token", TOKEN))
                .andExpect(status().isOk());
        verify(userStatusService).clearStatus(USER_ID);
    }

    @Test
    @DisplayName("在线状态写操作不得用 GET")
    void clearStatusRejectsGet() throws Exception {
        statusMvc.perform(get("/userStatus/clear").header("token", TOKEN))
                .andExpect(status().isMethodNotAllowed());
        verify(userStatusService, never()).clearStatus(anyString());
    }
}
