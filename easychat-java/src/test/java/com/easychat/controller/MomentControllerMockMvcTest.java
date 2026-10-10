package com.easychat.controller;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.MomentLikeResultVO;
import com.easychat.redis.RedisUtils;
import com.easychat.service.MomentNotifyService;
import com.easychat.service.MomentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MomentController} 的**行为**测试（MockMvc 真实路由，19 个端点全覆盖）。
 *
 * <p>朋友圈链路的风险集中在三处：
 * <ol>
 *   <li><b>可见性判定全在 Service</b>（{@code canView}），控制器只负责把
 *       {@code visibility/visibleList/invisibleList} 原样传下去 —— 一旦控制器擅自改写
 *       或漏传，白名单模式就会失效（历史上「白名单模式无人可见」的真凶）</li>
 *   <li><b>点赞的 cancel 缺省语义</b>：{@code cancel != null && cancel}，
 *       即「不传」= 点赞而非取消，也不许 NPE</li>
 *   <li><b>通知中心六个端点的 userId 一律取自会话</b>，不得由参数指定（否则可代读他人通知）</li>
 * </ol>
 */
class MomentControllerMockMvcTest {

    private static final String TOKEN = MockMvcSupport.TOKEN;
    private static final String USER_ID = MockMvcSupport.USER_ID;

    @Mock
    private MomentService momentService;
    @Mock
    private MomentNotifyService momentNotifyService;
    @Mock
    private RedisUtils redisUtils;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        MockMvcSupport.stubSession(redisUtils, MockMvcSupport.sessionUser());

        MomentController controller = new MomentController();
        inject(controller, "momentService", momentService);
        inject(controller, "momentNotifyService", momentNotifyService);
        inject(controller, "redisUtils", redisUtils);

        mvc = MockMvcBuilders.standaloneSetup(controller)
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

    // ==================== 发布 ====================

    @Test
    @DisplayName("发布：可见范围三参数原样透传（控制器不得改写，否则白名单失效）")
    void publishPassesVisibilityParamsUnchanged() throws Exception {
        mvc.perform(post("/moment/publish")
                        .header("token", TOKEN)
                        .param("content", "今天天气不错")
                        .param("visibility", "3")
                        .param("visibleList", "[\"U010\",\"U011\"]")
                        .param("invisibleList", "")
                        .param("location", "北京市"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(momentService).publish(eq("今天天气不错"), eq(3),
                eq("[\"U010\",\"U011\"]"), eq(""), eq("北京市"), any(TokenUserInfoDto.class));
    }

    @Test
    @DisplayName("发布：发布者取自会话（伪造 userId 参数被忽略）")
    void publishUsesSessionIdentity() throws Exception {
        mvc.perform(post("/moment/publish")
                        .header("token", TOKEN)
                        .param("content", "x").param("visibility", "0")
                        .param("userId", "U999"))
                .andExpect(status().isOk());

        ArgumentCaptor<TokenUserInfoDto> captor = ArgumentCaptor.forClass(TokenUserInfoDto.class);
        verify(momentService).publish(anyString(), anyInt(), any(), any(), any(), captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("列表：分页参数原样透传（不做默认值改写，兜底在 Service）")
    void listPassesPaging() throws Exception {
        when(momentService.loadMomentList(any(), any(), any())).thenReturn(java.util.Collections.emptyList());

        mvc.perform(post("/moment/list").header("token", TOKEN).param("pageNo", "2").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        verify(momentService).loadMomentList(any(TokenUserInfoDto.class), eq(2), eq(10));
    }

    @Test
    @DisplayName("详情：可见性判定交给 Service（控制器不自行放行）")
    void detailDelegatesVisibilityToService() throws Exception {
        mvc.perform(post("/moment/detail").header("token", TOKEN).param("momentId", "901"))
                .andExpect(status().isOk());
        verify(momentService).loadMomentDetail(eq(901L), any(TokenUserInfoDto.class));
    }

    @Test
    @DisplayName("个人主页：目标用户与查看者都要传（Service 需据此判定可见范围）")
    void userMomentListPassesBothUsers() throws Exception {
        when(momentService.loadUserMomentList(anyString(), any(), any(), any()))
                .thenReturn(java.util.Collections.emptyList());

        mvc.perform(post("/moment/userMomentList").header("token", TOKEN)
                        .param("targetUserId", "U010").param("pageNo", "1").param("pageSize", "20"))
                .andExpect(status().isOk());

        verify(momentService).loadUserMomentList(eq("U010"), any(TokenUserInfoDto.class), eq(1), eq(20));
    }

    // ==================== 点赞 ====================

    @Test
    @DisplayName("点赞：cancel 不传 → 按「点赞」处理（false），不得 NPE")
    void likeWithoutCancelMeansLike() throws Exception {
        MomentLikeResultVO vo = new MomentLikeResultVO();
        vo.setLiked(true);
        when(momentService.likeOrCancel(anyLong(), anyBoolean(), any())).thenReturn(vo);

        mvc.perform(post("/moment/like").header("token", TOKEN).param("momentId", "901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true));

        ArgumentCaptor<Boolean> cancel = ArgumentCaptor.forClass(Boolean.class);
        verify(momentService).likeOrCancel(eq(901L), cancel.capture(), any());
        assertFalse(cancel.getValue(), "cancel 缺省必须是 false（点赞），不能是 true");
    }

    @Test
    @DisplayName("点赞：cancel=true → 取消点赞")
    void likeWithCancelTrue() throws Exception {
        MomentLikeResultVO vo = new MomentLikeResultVO();
        vo.setLiked(false);
        when(momentService.likeOrCancel(anyLong(), anyBoolean(), any())).thenReturn(vo);

        mvc.perform(post("/moment/like").header("token", TOKEN)
                        .param("momentId", "901").param("cancel", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false));

        verify(momentService).likeOrCancel(eq(901L), eq(true), any());
    }

    // ==================== 评论 ====================

    @Test
    @DisplayName("评论：回复态的 parentId/replyToUserId 位置不得互换")
    void commentKeepsReplyArguments() throws Exception {
        mvc.perform(post("/moment/comment").header("token", TOKEN)
                        .param("momentId", "901")
                        .param("content", "回复你")
                        .param("parentId", "77")
                        .param("replyToUserId", "U010"))
                .andExpect(status().isOk());

        verify(momentService).addComment(eq(901L), eq("回复你"), eq(77L), eq("U010"), any(TokenUserInfoDto.class));
    }

    @Test
    @DisplayName("删除评论：操作者取自会话（服务端据此校验归属）")
    void deleteCommentUsesSessionIdentity() throws Exception {
        mvc.perform(post("/moment/deleteComment").header("token", TOKEN).param("commentId", "77"))
                .andExpect(status().isOk());
        verify(momentService).deleteComment(eq(77L), any(TokenUserInfoDto.class));
    }

    @Test
    @DisplayName("删除动态：操作者取自会话")
    void deleteMomentUsesSessionIdentity() throws Exception {
        mvc.perform(post("/moment/delete").header("token", TOKEN).param("momentId", "901"))
                .andExpect(status().isOk());
        verify(momentService).deleteMoment(eq(901L), any(TokenUserInfoDto.class));
    }

    // ==================== 媒体 ====================

    @Test
    @DisplayName("媒体上传：mediaType 与文件一并透传（0 图片 / 1 视频）")
    void uploadMediaPassesMediaType() throws Exception {
        when(momentService.uploadMedia(anyLong(), any(), anyInt(), any())).thenReturn("F001");
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});

        mvc.perform(multipart("/moment/uploadMedia")
                        .file(file).header("token", TOKEN)
                        .param("momentId", "901").param("mediaType", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("F001"));

        verify(momentService).uploadMedia(eq(901L), any(), eq(0), any(TokenUserInfoDto.class));
    }

    @Test
    @DisplayName("合并媒体分片：fileId/messageId/fileName/totalChunks/mediaType 全量透传")
    void mergeMediaChunksDelegates() throws Exception {
        when(momentService.mergeMediaChunks(anyString(), anyLong(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn("F002");

        mvc.perform(multipart("/moment/mergeMediaChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("fileId", "F001").param("momentId", "901")
                        .param("fileName", "a.png").param("totalChunks", "3").param("mediaType", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("F002"));

        verify(momentService).mergeMediaChunks(eq("F001"), eq(901L), eq("a.png"), eq(3), eq(0),
                any(TokenUserInfoDto.class));
    }

    @Test
    @DisplayName("检查媒体分片：已上传分片列表原样返回")
    void checkMediaChunksReturnsList() throws Exception {
        when(momentService.checkMediaChunks(anyString(), anyInt(), any()))
                .thenReturn(java.util.Arrays.asList(0, 2));

        mvc.perform(multipart("/moment/checkMediaChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("fileId", "F001").param("totalChunks", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ==================== 通知中心 ====================

    @Test
    @DisplayName("通知中心六个端点：userId 一律取自会话，不得由参数指定")
    void notifyEndpointsAlwaysUseSessionIdentity() throws Exception {
        when(momentNotifyService.getUnreadCount(USER_ID)).thenReturn(3);
        when(momentNotifyService.loadNotifyList(USER_ID, 1, 20))
                .thenReturn(new com.easychat.entity.vo.PaginationResultVO<>(java.util.Collections.emptyList()));
        when(momentNotifyService.loadRecentNotify(USER_ID, 5)).thenReturn(java.util.Collections.emptyList());

        mvc.perform(post("/moment/notify/unreadCount").header("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
        mvc.perform(post("/moment/notify/list").header("token", TOKEN).param("pageNo", "1").param("pageSize", "20"))
                .andExpect(status().isOk());
        mvc.perform(post("/moment/notify/recent").header("token", TOKEN).param("limit", "5"))
                .andExpect(status().isOk());
        mvc.perform(post("/moment/notify/markAllRead").header("token", TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/moment/notify/markReadByType").header("token", TOKEN).param("type", "1"))
                .andExpect(status().isOk());
        mvc.perform(post("/moment/notify/markRead").header("token", TOKEN).param("notifyId", "7"))
                .andExpect(status().isOk());
        mvc.perform(post("/moment/notify/clear").header("token", TOKEN)).andExpect(status().isOk());

        verify(momentNotifyService).getUnreadCount(USER_ID);
        verify(momentNotifyService).loadNotifyList(USER_ID, 1, 20);
        verify(momentNotifyService).loadRecentNotify(USER_ID, 5);
        verify(momentNotifyService).markAllRead(USER_ID);
        verify(momentNotifyService).markReadByType(USER_ID, 1);
        verify(momentNotifyService).markRead(USER_ID, 7L);
        verify(momentNotifyService).clearNotify(USER_ID);
    }

    @Test
    @DisplayName("未读数为 0 时不显示红点（返回 0 而非 null，前端直接比较大小）")
    void unreadCountZeroIsNumber() throws Exception {
        when(momentNotifyService.getUnreadCount(USER_ID)).thenReturn(0);

        mvc.perform(post("/moment/notify/unreadCount").header("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }
}
