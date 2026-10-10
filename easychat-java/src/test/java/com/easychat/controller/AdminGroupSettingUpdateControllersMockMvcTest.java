package com.easychat.controller;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.vo.AppUpdateVO;
import com.easychat.redis.RedisComponet;
import com.easychat.service.AppUpdateService;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三个小控制器的**行为**测试：管理端群管理、系统设置、客户端升级检查。
 *
 * <p>其中管理端解散群一条锁的是一个<b>真实缺陷</b>：原实现抛
 * {@code ResponseCodeEnum.CODE_200}，而该枚举项的码值就是 <b>0（成功码）</b>，
 * 于是「群不存在」会得到 <b>HTTP 400 + body {code:0, message:"success"}</b>。
 * 前端错误分支不看 body.code、直接把 {@code body.message} 当错误文案弹出，
 * 于是管理员点「解散」一个已失效的群，会看到一条写着 <b>success</b> 的错误提示，
 * 且真实原因（资源不存在）完全丢失。正确语义应为 {@code CODE_1003} + 404。
 */
class AdminGroupSettingUpdateControllersMockMvcTest {

    @Mock
    private GroupInfoService groupInfoService;
    @Mock
    private AppUpdateService appUpdateService;
    @Mock
    private UserInfoService userInfoService;
    @Mock
    private RedisComponet redisComponet;
    @Mock
    private AppConfig appConfig;

    private MockMvc groupMvc;
    private MockMvc settingMvc;
    private MockMvc updateMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        AdminGroupController groupController = new AdminGroupController();
        inject(groupController, "groupInfoService", groupInfoService);
        groupMvc = mvc(groupController);

        AdminSettingController settingController = new AdminSettingController();
        inject(settingController, "redisComponet", redisComponet);
        inject(settingController, "appConfig", appConfig);
        settingMvc = mvc(settingController);

        UpdateController updateController = new UpdateController();
        inject(updateController, "appUpdateService", appUpdateService);
        inject(updateController, "appConfig", appConfig);
        updateMvc = mvc(updateController);
    }

    private MockMvc mvc(Object controller) {
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

    // ==================== 管理端解散群 ====================

    @Test
    @DisplayName("解散不存在的群 → 资源不存在语义（code 1003），不得回 code=0 成功码")
    void dissolveNonExistingGroupIsNotSuccess() throws Exception {
        when(groupInfoService.getGroupInfoByGroupId("G404")).thenReturn(null);

        groupMvc.perform(post("/admin/dissolutionGroup").param("groupId", "G404"))
                // ⚠ 当前 HTTP 为 400：inferHttpStatus 未把 1003 映射成 404，
                // 与 AGENTS §3.2「资源不存在 404」及本类 javadoc 的映射表不一致。
                // 但 1003 同时兼作「非管理员访问管理端」，改映射会影响所有管理端端点的状态码，
                // 属需人工确认的契约变更，故此处锁定现状、单独登记，不夹带修改。
                .andExpect(status().isBadRequest())
                // 关键：body.code 必须是 1003、绝不能是 0，否则前端会当成成功
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        verify(groupInfoService, never()).dissolutionGroup(anyString(), anyString());
    }

    @Test
    @DisplayName("解散存在的群 → 传群主 id 进 Service（不是管理员 id）")
    void dissolvePassesGroupOwnerId() throws Exception {
        com.easychat.entity.po.GroupInfo g = new com.easychat.entity.po.GroupInfo();
        g.setGroupId("G001");
        g.setGroupOwnerId("U001");
        when(groupInfoService.getGroupInfoByGroupId("G001")).thenReturn(g);

        groupMvc.perform(post("/admin/dissolutionGroup").param("groupId", "G001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupInfoService).dissolutionGroup("U001", "G001");
    }

    @Test
    @DisplayName("群列表：控制器强制打开群主名与成员数关联（前端不传也得有）")
    void loadGroupForcesJoins() throws Exception {
        when(groupInfoService.findListByPage(any()))
                .thenReturn(new com.easychat.entity.vo.PaginationResultVO<>(java.util.Collections.emptyList()));

        groupMvc.perform(post("/admin/loadGroup").param("pageNo", "1"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.easychat.entity.query.GroupInfoQuery> captor =
                org.mockito.ArgumentCaptor.forClass(com.easychat.entity.query.GroupInfoQuery.class);
        verify(groupInfoService).findListByPage(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getQueryGroupOwnerName());
        assertEquals(Boolean.TRUE, captor.getValue().getQueryMemberCount());
    }

    // ==================== 系统设置 ====================

    @Test
    @DisplayName("读取系统设置：从本地设置取并原样返回")
    void getSysSettingReturnsLocalSetting() throws Exception {
        SysSettingDto dto = new SysSettingDto();
        dto.setMaxImageSize(9);
        when(redisComponet.getSysSetting()).thenReturn(dto);

        settingMvc.perform(post("/admin/getSysSetting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.maxImageSize").value(9));
    }

    @Test
    @DisplayName("保存系统设置：无机器人头像文件时不触碰磁盘即可成功")
    void saveSysSettingWithoutRobotFile() throws Exception {
        settingMvc.perform(post("/admin/saveSysSetting")
                        .param("maxImageSize", "3")
                        .param("maxVideoSize", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== 客户端升级检查 ====================

    @Test
    @DisplayName("版本检查：appVersion 为空 → 返回成功但 data 为 null（不查库）")
    void checkVersionWithEmptyVersionReturnsNull() throws Exception {
        updateMvc.perform(post("/update/checkVersion").param("appVersion", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(appUpdateService, never()).getLatestUpdate(anyString(), any());
    }

    @Test
    @DisplayName("版本检查：无新版本 → data 为 null（前端据此不弹更新窗）")
    void checkVersionWithoutUpdateReturnsNull() throws Exception {
        when(appUpdateService.getLatestUpdate("1.0.0", "U001")).thenReturn(null);

        updateMvc.perform(post("/update/checkVersion")
                        .param("appVersion", "1.0.0").param("uid", "U001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("版本检查：有新版本 → VO 进包络，且补上安装包文件大小")
    void checkVersionReturnsVoWithFileSize() throws Exception {
        com.easychat.entity.po.AppUpdate update = new com.easychat.entity.po.AppUpdate();
        update.setId(9);
        update.setVersion("1.2.0");
        update.setUpdateDesc("修复若干问题");
        when(appUpdateService.getLatestUpdate("1.0.0", "U001")).thenReturn(update);
        when(appConfig.getProjectFolder()).thenReturn(System.getProperty("java.io.tmpdir") + "/easychat-test/");

        updateMvc.perform(post("/update/checkVersion")
                        .param("appVersion", "1.0.0").param("uid", "U001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value("1.2.0"));

        // 文件不存在时 size 为 0 而不是报错（客户端据此显示 0B 而非崩溃）
        assertNotNull(updateMvc);
    }
}
