package com.easychat.controller;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.po.AppUpdate;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.redis.RedisComponet;
import com.easychat.redis.RedisUtils;
import com.easychat.service.AdminCallLogService;
import com.easychat.service.AdminReportService;
import com.easychat.service.AppUpdateService;
import com.easychat.service.SensitiveWordAdminService;
import com.easychat.service.UserInfoBeautyService;
import com.easychat.service.UserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 管理端控制器的**行为**测试（8 个控制器合并一个文件）。
 *
 * <p>管理端鉴权由 {@code GlobalInterceptorAnnotationContractTest} 反射断言
 * （「{@code /admin} 前缀必须 checkAdmin=true」），此处不重复，只验控制器自身职责：
 * <ol>
 *   <li>分页查询的排序<b>由控制器钉死 SortOption</b>（不接受前端传入的排序字段）</li>
 *   <li>委托参数逐个透传，含分页 pageNo/pageSize 与业务参数</li>
 *   <li>敏感词导入必须把 file/level/status 三者<b>一并</b>传给 Service
 *       —— level/status 缺一是历史上「非法 level 词条静默失效」的入口</li>
 *   <li>导出走 GET（幂等查询），其余写操作走 POST</li>
 *   <li>业务异常透传（2702/2703 等），不得被吞成成功</li>
 * </ol>
 */
class AdminControllersMockMvcTest {

    @Mock
    private AppUpdateService appUpdateService;
    @Mock
    private AdminCallLogService adminCallLogService;
    @Mock
    private AdminReportService adminReportService;
    @Mock
    private SensitiveWordAdminService sensitiveWordAdminService;
    @Mock
    private UserInfoBeautyService userInfoBeautyService;
    @Mock
    private UserInfoService userInfoService;
    @Mock
    private RedisComponet redisComponet;
    /**
     * 管理端处置类端点要从会话取管理员身份（{@code dealReport}），
     * 基类私有字段不注入 mock 会 NPE→500，看起来像业务缺陷。
     */
    @Mock
    private RedisUtils redisUtils;

    private MockMvc updateMvc;
    private MockMvc callLogMvc;
    private MockMvc reportMvc;
    private MockMvc wordMvc;
    private MockMvc beautyMvc;
    private MockMvc userMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        MockMvcSupport.stubSession(redisUtils, MockMvcSupport.sessionUser("A001", MockMvcSupport.TOKEN, "管理员"));
        updateMvc = build(new AdminAppUpdateController(), "appUpdateService", appUpdateService);
        callLogMvc = build(new AdminCallLogController(), "adminCallLogService", adminCallLogService);
        reportMvc = build(new AdminReportController(), "adminReportService", adminReportService);
        wordMvc = build(new AdminSensitiveWordController(), "sensitiveWordAdminService", sensitiveWordAdminService);
        beautyMvc = build(new AdminUserInfoBeautyController(), "userInfoBeautyService", userInfoBeautyService);
        userMvc = build(new AdminUserInfoController(), "userInfoService", userInfoService);
    }

    private MockMvc build(Object controller, String serviceField, Object service) {
        inject(controller, serviceField, service);
        inject(controller, "redisComponet", redisComponet);
        inject(controller, "redisUtils", redisUtils);
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

    // ==================== 版本管理 ====================

    @Test
    @DisplayName("版本列表：排序被控制器钉死为 ID 倒序（前端传的 sortField 不参与）")
    void loadUpdateListPinsSortOption() throws Exception {
        when(appUpdateService.findListByPage(any()))
                .thenReturn(new PaginationResultVO<>(Collections.emptyList()));

        updateMvc.perform(post("/admin/loadUpdateList")
                        .param("sortField", "id")   // 攻击者/前端乱传
                        .param("sortOrder", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<com.easychat.entity.query.AppUpdateQuery> captor =
                ArgumentCaptor.forClass(com.easychat.entity.query.AppUpdateQuery.class);
        verify(appUpdateService).findListByPage(captor.capture());
        assertEquals(SortOption.APP_UPDATE_ID_DESC, captor.getValue().getSortOption());
    }

    @Test
    @DisplayName("发布更新：id/status/grayscaleUid 三参数透传")
    void postUpdateDelegates() throws Exception {
        updateMvc.perform(post("/admin/postUpdate")
                        .param("id", "7").param("status", "1").param("grayscaleUid", "[\"U001\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(appUpdateService).postUpdate(7, 1, "[\"U001\"]");
    }

    @Test
    @DisplayName("删除不存在的版本 → 1003 透传（Service 已补判空，不得变成 500）")
    void delUpdateNotFoundPropagates() throws Exception {
        when(appUpdateService.getAppUpdateById(999)).thenReturn(null);
        org.mockito.Mockito.doThrow(new BusinessException(ResponseCodeEnum.CODE_1003))
                .when(appUpdateService).deleteAppUpdateById(999);

        updateMvc.perform(post("/admin/delUpdate").param("id", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    @DisplayName("版本写操作不得用 GET")
    void delUpdateRejectsGet() throws Exception {
        updateMvc.perform(get("/admin/delUpdate").param("id", "1"))
                .andExpect(status().isMethodNotAllowed());
        verify(appUpdateService, never()).deleteAppUpdateById(any());
    }

    // ==================== 通话记录 ====================

    @Test
    @DisplayName("通话记录列表：分页参数原样透传")
    void loadCallLogPassesPaging() throws Exception {
        when(adminCallLogService.loadCallLog(any()))
                .thenReturn(new PaginationResultVO<>(Collections.emptyList()));

        callLogMvc.perform(post("/admin/callLog/loadCallLog").param("pageNo", "2").param("pageSize", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<com.easychat.entity.query.CallLogQuery> captor =
                ArgumentCaptor.forClass(com.easychat.entity.query.CallLogQuery.class);
        verify(adminCallLogService).loadCallLog(captor.capture());
        assertEquals(Integer.valueOf(2), captor.getValue().getPageNo());
        assertEquals(Integer.valueOf(15), captor.getValue().getPageSize());
    }

    // ==================== 举报处置 ====================

    @Test
    @DisplayName("举报详情：reportType 一并传入（决定按消息还是按动态处置）")
    void getReportDetailPassesType() throws Exception {
        reportMvc.perform(post("/admin/report/getReportDetail").param("id", "50").param("reportType", "2"))
                .andExpect(status().isOk());
        verify(adminReportService).getReportDetail(50L, 2);
    }

    @Test
    @DisplayName("举报处置：处置人取自会话（不得由参数指定），2703 动作非法原样透传")
    void dealReportPropagatesBusinessCode() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessException(ResponseCodeEnum.CODE_2703))
                .when(adminReportService).dealReport(anyLong(), any(), any(), any(), anyString(), any());

        reportMvc.perform(post("/admin/report/dealReport")
                        .header("token", MockMvcSupport.TOKEN)
                        .param("id", "50").param("reportType", "2").param("status", "1")
                        .param("handleAction", "1").param("handleNote", "已处理")
                        .param("adminId", "A999"))   // 伪造：必须被忽略
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2703));

        ArgumentCaptor<com.easychat.entity.dto.TokenUserInfoDto> admin =
                ArgumentCaptor.forClass(com.easychat.entity.dto.TokenUserInfoDto.class);
        verify(adminReportService).dealReport(eq(50L), eq(2), eq(1), eq(1), eq("已处理"), admin.capture());
        assertEquals("A001", admin.getValue().getUserId());
    }

    @Test
    @DisplayName("处置审计日志：列表端点可用且返回包络")
    void loadAuditLogDelegates() throws Exception {
        when(adminReportService.loadAuditLog(any()))
                .thenReturn(new PaginationResultVO<>(Collections.emptyList()));

        reportMvc.perform(post("/admin/report/loadAuditLog").param("reportId", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(adminReportService).loadAuditLog(any());
    }

    // ==================== 敏感词 ====================

    @Test
    @DisplayName("敏感词导入：file/level/status 三者一并传入（缺 level 会让非法等级词条入库）")
    void importWordsPassesLevelAndStatus() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "w.txt", "text/plain", "坏词".getBytes());
        when(sensitiveWordAdminService.importWords(any(), any(), any()))
                .thenReturn(new com.easychat.entity.vo.ImportResultVO());

        wordMvc.perform(multipart("/admin/sensitiveWord/importWords")
                        .file(file)
                        .param("level", "3")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(sensitiveWordAdminService).importWords(any(), eq(3), eq(1));
    }

    @Test
    @DisplayName("敏感词导入：level 越界由 Service 拒绝并透传 1001（控制器不得放行）")
    void importWordsRejectsIllegalLevel() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "w.txt", "text/plain", "坏词".getBytes());
        when(sensitiveWordAdminService.importWords(any(), any(), any()))
                .thenThrow(new BusinessException(ResponseCodeEnum.CODE_1001, "敏感词等级非法"));

        wordMvc.perform(multipart("/admin/sensitiveWord/importWords")
                        .file(file).param("level", "4").param("status", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("导出敏感词走 GET（幂等查询）并原样返回字节流")
    void exportWordsUsesGet() throws Exception {
        byte[] csv = "词条,等级,状态\n".getBytes();
        when(sensitiveWordAdminService.exportWords()).thenReturn(csv);

        byte[] body = wordMvc.perform(get("/admin/sensitiveWord/exportWords"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(csv, body);
    }

    @Test
    @DisplayName("删除敏感词：id 透传")
    void deleteWordDelegates() throws Exception {
        wordMvc.perform(post("/admin/sensitiveWord/deleteWord").param("id", "3"))
                .andExpect(status().isOk());
        verify(sensitiveWordAdminService).deleteWord(3L);
    }

    // ==================== 靓号池 ====================

    @Test
    @DisplayName("靓号删除：返回受影响条数进包络（前端据此提示是否真的删掉了）")
    void delBeautyAccountReturnsCount() throws Exception {
        when(userInfoBeautyService.deleteUserInfoBeautyById(5)).thenReturn(1);

        beautyMvc.perform(post("/admin/delBeautAccount").param("id", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("靓号列表：分页参数透传")
    void loadBeautyAccountPassesPaging() throws Exception {
        when(userInfoBeautyService.findListByPage(any()))
                .thenReturn(new PaginationResultVO<>(Collections.emptyList()));

        beautyMvc.perform(post("/admin/loadBeautyAccountList")
                        .param("pageNo", "4").param("pageSize", "20"))
                .andExpect(status().isOk());

        ArgumentCaptor<com.easychat.entity.query.UserInfoBeautyQuery> captor =
                ArgumentCaptor.forClass(com.easychat.entity.query.UserInfoBeautyQuery.class);
        verify(userInfoBeautyService).findListByPage(captor.capture());
        assertEquals(Integer.valueOf(4), captor.getValue().getPageNo());
    }

    // ==================== 用户管理 ====================

    @Test
    @DisplayName("强制下线：只传 userId（不传操作者，由审计切面补 IP）")
    void forceOffLineDelegates() throws Exception {
        userMvc.perform(post("/admin/forceOffLine").param("userId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(userInfoService).forceOffLine("U010");
    }

    @Test
    @DisplayName("更新用户状态：status 与 userId 位置不得互换")
    void updateUserStatusKeepsArgumentOrder() throws Exception {
        userMvc.perform(post("/admin/updateUserStatus")
                        .param("status", "1").param("userId", "U010"))
                .andExpect(status().isOk());
        verify(userInfoService).updateUserStatus(1, "U010");
    }

    @Test
    @DisplayName("用户列表：排序在 Service 侧解析，控制器不注入 sortField（防排序注入）")
    void loadUserDoesNotInjectSortField() throws Exception {
        when(userInfoService.findListByPage(any()))
                .thenReturn(new PaginationResultVO<>(Collections.emptyList()));

        userMvc.perform(post("/admin/loadUser")
                        .param("sortField", "password").param("sortOrder", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<com.easychat.entity.query.UserInfoQuery> captor =
                ArgumentCaptor.forClass(com.easychat.entity.query.UserInfoQuery.class);
        verify(userInfoService).findListByPage(captor.capture());
        // 控制器原样把 query 交给 Service，由 SortWhitelistTools 解析（sortField 非法会在 Service 报错）
        assertNotNull(captor.getValue());
    }
}
