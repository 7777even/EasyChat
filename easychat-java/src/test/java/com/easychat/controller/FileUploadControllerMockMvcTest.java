package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.vo.Result;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisUtils;
import com.easychat.service.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FileUploadController} 的**行为**测试（MockMvc 真实路由）。
 *
 * <p>被测点：
 * <ol>
 *   <li>三个端点均为 POST（写操作不得用 GET，§3.5）</li>
 *   <li><b>身份只能来自会话</b>：传给 Service 的 {@code TokenUserInfoDto} 取自
 *       {@code token} 请求头对应的 Redis 会话，请求里伪造的 userId 参数<b>必须被忽略</b>——
 *       这是越权上传的唯一防线，一旦改成读参数即全线失守</li>
 *   <li>委托参数顺序：{@code (fileId, chunkIndex, totalChunks, chunk, user)}
 *       —— chunkIndex/totalChunks 相邻且同为 Integer，顺序写反不会编译报错，是典型静默缺陷点</li>
 *   <li>合并分片透传 messageId(Long) / fileName / totalChunks / cover</li>
 *   <li>checkChunks 的返回值原样进包络 data</li>
 *   <li>业务异常透传（文件不存在 2601 等），不被 Controller 吞成成功</li>
 * </ol>
 */
class FileUploadControllerMockMvcTest {

    private static final String TOKEN = "tok-abc";
    private static final String USER_ID = "U001";

    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private RedisUtils redisUtils;

    @InjectMocks
    private FileUploadController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
        givenSessionUser();
    }

    private void givenSessionUser() {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setToken(TOKEN);
        dto.setUserId(USER_ID);
        dto.setNickName("小明");
        when(redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + TOKEN)).thenReturn(dto);
    }

    // ==================== 路由方法 ====================

    @Test
    @DisplayName("写操作不得用 GET：GET /upload/checkChunks 应 405")
    void checkChunksRejectsGet() throws Exception {
        mockMvc.perform(get("/upload/checkChunks").header("token", TOKEN))
                .andExpect(status().isMethodNotAllowed());
        verify(fileUploadService, never()).checkUploadedChunks(anyString(), any(), any());
    }

    // ==================== 身份来源（越权防线） ====================

    @Test
    @DisplayName("checkChunks：身份取自 token 会话，伪造的 userId 参数被忽略")
    void identityComesFromSessionNotFromParameter() throws Exception {
        when(fileUploadService.checkUploadedChunks(anyString(), any(), any())).thenReturn(Arrays.asList(0, 2));

        mockMvc.perform(multipart("/upload/checkChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        // 攻击者伪造：期望被完全忽略
                        .param("userId", "U999")
                        .param("fileId", "F001")
                        .param("totalChunks", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        ArgumentCaptor<TokenUserInfoDto> captor = ArgumentCaptor.forClass(TokenUserInfoDto.class);
        verify(fileUploadService).checkUploadedChunks(eq("F001"), eq(5), captor.capture());
        // 必须是会话里的本人，而不是参数里的 U999
        assertEquals(USER_ID, captor.getValue().getUserId());
    }

    @Test
    @DisplayName("uploadChunk：身份同样取自会话")
    void uploadChunkIdentityComesFromSession() throws Exception {
        MockMultipartFile chunk = new MockMultipartFile("chunk", "c0", "application/octet-stream", new byte[]{1, 2});

        mockMvc.perform(multipart("/upload/uploadChunk")
                        .file(chunk)
                        .header("token", TOKEN)
                        .param("userId", "U999")
                        .param("fileId", "F001")
                        .param("chunkIndex", "0")
                        .param("totalChunks", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<TokenUserInfoDto> captor = ArgumentCaptor.forClass(TokenUserInfoDto.class);
        verify(fileUploadService).uploadChunk(eq("F001"), eq(0), eq(3), any(), captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
    }

    // ==================== 委托参数（顺序即契约） ====================

    @Test
    @DisplayName("uploadChunk：chunkIndex 与 totalChunks 位置不得互换")
    void uploadChunkKeepsArgumentOrder() throws Exception {
        MockMultipartFile chunk = new MockMultipartFile("chunk", "c2", "application/octet-stream", new byte[]{9});

        mockMvc.perform(multipart("/upload/uploadChunk")
                        .file(chunk)
                        .header("token", TOKEN)
                        .param("fileId", "F001")
                        .param("chunkIndex", "2")   // 第 3 片
                        .param("totalChunks", "5")) // 共 5 片
                .andExpect(status().isOk());

        ArgumentCaptor<Integer> chunkIndex = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> totalChunks = ArgumentCaptor.forClass(Integer.class);
        verify(fileUploadService).uploadChunk(eq("F001"), chunkIndex.capture(), totalChunks.capture(), any(), any());
        assertEquals(2, chunkIndex.getValue());
        assertEquals(5, totalChunks.getValue());
    }

    @Test
    @DisplayName("mergeChunks：messageId / fileName / totalChunks 透传，cover 可缺省为 null")
    void mergeChunksDelegatesAllArguments() throws Exception {
        mockMvc.perform(multipart("/upload/mergeChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("fileId", "F001")
                        .param("messageId", "8888")
                        .param("fileName", "a.png")
                        .param("totalChunks", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(fileUploadService).mergeChunks(eq("F001"), eq(8888L), eq("a.png"), eq(4), any(), any());
    }

    @Test
    @DisplayName("checkChunks：已上传分片列表原样返回（空列表也是有效结果，不得报成功以外的码）")
    void checkChunksReturnsEmptyListAsSuccess() throws Exception {
        when(fileUploadService.checkUploadedChunks(anyString(), any(), any())).thenReturn(java.util.Collections.emptyList());

        mockMvc.perform(multipart("/upload/checkChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("fileId", "F001")
                        .param("totalChunks", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ==================== 异常透传 ====================

    @Test
    @DisplayName("业务异常透传：文件不存在(2601) 原样出现在响应中")
    void fileNotFoundPropagates() throws Exception {
        when(fileUploadService.checkUploadedChunks(anyString(), any(), any()))
                .thenThrow(new BusinessException(com.easychat.entity.enums.ResponseCodeEnum.CODE_2601));

        mockMvc.perform(multipart("/upload/checkChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("fileId", "NOPE")
                        .param("totalChunks", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2601));
    }

    @Test
    @DisplayName("合并分片失败 → 异常不得被吞成成功（否则前端以为已合并）")
    void mergeFailurePropagates() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(com.easychat.entity.enums.ResponseCodeEnum.CODE_1002))
                .when(fileUploadService).mergeChunks(anyString(), anyLong(), anyString(), any(), any(), any());

        mockMvc.perform(multipart("/upload/mergeChunks")
                        .file(new MockMultipartFile("dummy", new byte[0]))
                        .header("token", TOKEN)
                        .param("fileId", "F001")
                        .param("messageId", "8888")
                        .param("fileName", "a.png")
                        .param("totalChunks", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }
}
