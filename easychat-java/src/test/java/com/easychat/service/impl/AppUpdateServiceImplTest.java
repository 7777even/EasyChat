package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.enums.AppUpdateFileTypeEnum;
import com.easychat.entity.enums.AppUpdateSatusEnum;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.po.AppUpdate;
import com.easychat.entity.query.AppUpdateQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.AppUpdateMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppUpdateServiceImpl 单元测试。
 *
 * <p>App 发布管理是管理端写路径：「仅未发布（INIT）
 * 记录可删改」、「新版本号必须大于最新版本」、「版本号
 * 唯一」、「灰度发布必须指定灰度用户」与「非灰度清空
 * 灰度名单」是其核心契约，漏一项会发出错误版本或
 * 覆盖线上版本。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AppUpdateServiceImplTest {

    private static final Integer INIT_ID = 9;
    private static final String LATEST_VERSION = "1.2.0";
    private static final String NEW_VERSION = "1.3.0";

    @InjectMocks
    private AppUpdateServiceImpl appUpdateService;

    @Mock
    private AppConfig appConfig;

    @Mock
    private AppUpdateMapper<AppUpdate, AppUpdateQuery> appUpdateMapper;

    private AppUpdate recordOf(int id, String version, Integer status) {
        AppUpdate record = new AppUpdate();
        record.setId(id);
        record.setVersion(version);
        record.setStatus(status);
        return record;
    }

    private AppUpdate initRecord(int id) {
        return recordOf(id, "1.0.0", AppUpdateSatusEnum.INIT.getStatus());
    }

    // ======================== 分页查询 ========================

    @Test
    public void findListByPage_success_defaultPageSize15() {
        when(appUpdateMapper.selectCount(any(AppUpdateQuery.class))).thenReturn(4);
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(recordOf(1, "1.0.0", 0)));

        AppUpdateQuery query = new AppUpdateQuery();
        PaginationResultVO<AppUpdate> result = appUpdateService.findListByPage(query);

        assertEquals(Integer.valueOf(4), result.getTotalCount());
        assertEquals(Integer.valueOf(PageSize.SIZE15.getSize()), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertNotNull(query.getSimplePage());
        assertEquals(1, result.getList().size());
    }

    // ======================== deleteAppUpdateById ========================

    @Test
    public void deleteAppUpdateById_notInit_throws1001() {
        // 已发布的记录不允许删除
        when(appUpdateMapper.selectById(5))
                .thenReturn(recordOf(5, "1.0.0", AppUpdateSatusEnum.ALL.getStatus()));
        try {
            appUpdateService.deleteAppUpdateById(5);
            fail("非 INIT 状态应拒绝删除");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void deleteAppUpdateById_init_deletes() {
        when(appUpdateMapper.selectById(5)).thenReturn(initRecord(5));
        appUpdateService.deleteAppUpdateById(5);
        verify(appUpdateMapper).deleteById(5);
    }

    // ======================== saveUpdate：校验 ========================

    @Test
    public void saveUpdate_invalidFileType_throws1001() throws Exception {
        AppUpdate bean = new AppUpdate();
        bean.setFileType(9);
        try {
            appUpdateService.saveUpdate(bean, null);
            fail("非法文件类型应拒绝");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void saveUpdate_editNotInit_throws1001() throws Exception {
        AppUpdate bean = new AppUpdate();
        bean.setId(5);
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        when(appUpdateMapper.selectById(5))
                .thenReturn(recordOf(5, "1.0.0", AppUpdateSatusEnum.GRAYSCALE.getStatus()));
        try {
            appUpdateService.saveUpdate(bean, null);
            fail("编辑非 INIT 记录应拒绝");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void saveUpdate_new_versionNotGreater_throwsWithMessage() throws Exception {
        AppUpdate bean = new AppUpdate();
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        bean.setVersion(LATEST_VERSION);
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(
                        recordOf(INIT_ID, LATEST_VERSION, AppUpdateSatusEnum.ALL.getStatus())));
        try {
            appUpdateService.saveUpdate(bean, null);
            fail("新版本号不大于最新版本应拒绝");
        } catch (BusinessException e) {
            assertEquals("当前版本必须大于历史版本", e.getMessage());
        }
    }

    @Test
    public void saveUpdate_edit_notLatest_versionNotGreater_throwsWithMessage() throws Exception {
        // 编辑非最新记录且版本号 >= 最新版本 → 拒绝（只有最新记录本人可改出 >= 的版本）
        AppUpdate bean = new AppUpdate();
        bean.setId(5);
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        bean.setVersion(NEW_VERSION);
        when(appUpdateMapper.selectById(5)).thenReturn(initRecord(5));
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(
                        recordOf(INIT_ID, LATEST_VERSION, AppUpdateSatusEnum.ALL.getStatus())));
        try {
            appUpdateService.saveUpdate(bean, null);
            fail("编辑非最新记录且版本号不低于最新版本应拒绝");
        } catch (BusinessException e) {
            assertEquals("当前版本必须大于历史版本", e.getMessage());
        }
    }

    @Test
    public void saveUpdate_edit_versionExists_throwsWithMessage() throws Exception {
        // 版本号已被另一条记录占用
        AppUpdate bean = new AppUpdate();
        bean.setId(INIT_ID);
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        bean.setVersion(NEW_VERSION);
        when(appUpdateMapper.selectById(INIT_ID)).thenReturn(initRecord(INIT_ID));
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(
                        recordOf(INIT_ID, LATEST_VERSION, AppUpdateSatusEnum.ALL.getStatus())));
        when(appUpdateMapper.selectByVersion(NEW_VERSION))
                .thenReturn(recordOf(7, NEW_VERSION, AppUpdateSatusEnum.ALL.getStatus()));
        try {
            appUpdateService.saveUpdate(bean, null);
            fail("版本号被占用应拒绝");
        } catch (BusinessException e) {
            assertEquals("版本号已存在", e.getMessage());
        }
    }

    // ======================== saveUpdate：落库 ========================

    @Test
    public void saveUpdate_new_versionGreater_insertsWithInitStatus() throws Exception {
        AppUpdate bean = new AppUpdate();
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        bean.setVersion(NEW_VERSION);
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(
                        recordOf(INIT_ID, LATEST_VERSION, AppUpdateSatusEnum.ALL.getStatus())));
        when(appUpdateMapper.selectByVersion(NEW_VERSION)).thenReturn(null);

        appUpdateService.saveUpdate(bean, null);

        // 新增：状态 INIT + 服务端创建时间
        ArgumentCaptor<AppUpdate> captor = ArgumentCaptor.forClass(AppUpdate.class);
        verify(appUpdateMapper).insert(captor.capture());
        AppUpdate inserted = captor.getValue();
        assertEquals(AppUpdateSatusEnum.INIT.getStatus(), inserted.getStatus());
        assertNotNull("创建时间由服务端填充", inserted.getCreateTime());
        // 最新记录查询按 ID 倒序取第一条
        ArgumentCaptor<AppUpdateQuery> queryCaptor = ArgumentCaptor.forClass(AppUpdateQuery.class);
        verify(appUpdateMapper).selectList(queryCaptor.capture());
        assertEquals(SortOption.APP_UPDATE_ID_DESC, queryCaptor.getValue().getSortOption());
    }

    @Test
    public void saveUpdate_edit_isLatest_updatesById() throws Exception {
        // 编辑最新记录本人：版本号可 >= 最新版本
        AppUpdate bean = new AppUpdate();
        bean.setId(INIT_ID);
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        bean.setVersion(NEW_VERSION);
        when(appUpdateMapper.selectById(INIT_ID)).thenReturn(initRecord(INIT_ID));
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(
                        recordOf(INIT_ID, LATEST_VERSION, AppUpdateSatusEnum.ALL.getStatus())));
        when(appUpdateMapper.selectByVersion(NEW_VERSION)).thenReturn(null);

        appUpdateService.saveUpdate(bean, null);

        verify(appUpdateMapper).updateById(any(AppUpdate.class), eq(INIT_ID));
    }

    @Test
    public void saveUpdate_filePersistedToAppFolder() throws Exception {
        // 携带安装包文件：落盘到 projectFolder + /app/ + id + .exe
        String tempDir = Files.createTempDirectory("app-update-test").toString();
        when(appConfig.getProjectFolder()).thenReturn(tempDir);

        AppUpdate bean = new AppUpdate();
        bean.setId(INIT_ID);
        bean.setFileType(AppUpdateFileTypeEnum.LOCAL.getType());
        bean.setVersion(NEW_VERSION);
        when(appUpdateMapper.selectById(INIT_ID)).thenReturn(initRecord(INIT_ID));
        when(appUpdateMapper.selectList(any(AppUpdateQuery.class)))
                .thenReturn(Collections.singletonList(
                        recordOf(INIT_ID, LATEST_VERSION, AppUpdateSatusEnum.ALL.getStatus())));
        when(appUpdateMapper.selectByVersion(NEW_VERSION)).thenReturn(null);

        MockMultipartFile file = new MockMultipartFile("file", "app.exe",
                "application/octet-stream", "exe-bytes".getBytes());
        appUpdateService.saveUpdate(bean, file);

        verify(appUpdateMapper).updateById(any(AppUpdate.class), eq(INIT_ID));
        File stored = new File(tempDir + Constants.APP_UPDATE_FOLDER
                + INIT_ID + Constants.APP_EXE_SUFFIX);
        assertTrue("安装包应落盘到 " + stored, stored.exists());
        assertTrue(stored.delete());
    }

    // ======================== postUpdate ========================

    @Test
    public void postUpdate_nullStatus_throws1001() {
        try {
            appUpdateService.postUpdate(5, null, "U_x");
            fail("状态为空应拒绝");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void postUpdate_grayscaleWithoutUid_throws1001() {
        try {
            appUpdateService.postUpdate(5, AppUpdateSatusEnum.GRAYSCALE.getStatus(), "");
            fail("灰度发布未指定灰度用户应拒绝");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
    }

    @Test
    public void postUpdate_grayscale_keepsUid() {
        appUpdateService.postUpdate(5, AppUpdateSatusEnum.GRAYSCALE.getStatus(), "U_gray");
        ArgumentCaptor<AppUpdate> captor = ArgumentCaptor.forClass(AppUpdate.class);
        verify(appUpdateMapper).updateById(captor.capture(), eq(5));
        assertEquals(AppUpdateSatusEnum.GRAYSCALE.getStatus(), captor.getValue().getStatus());
        assertEquals("U_gray", captor.getValue().getGrayscaleUid());
    }

    @Test
    public void postUpdate_nonGrayscale_clearsUid() {
        // 非灰度发布：灰度名单清空
        appUpdateService.postUpdate(5, AppUpdateSatusEnum.ALL.getStatus(), "U_gray");
        ArgumentCaptor<AppUpdate> captor = ArgumentCaptor.forClass(AppUpdate.class);
        verify(appUpdateMapper).updateById(captor.capture(), eq(5));
        assertEquals(AppUpdateSatusEnum.ALL.getStatus(), captor.getValue().getStatus());
        assertEquals("", captor.getValue().getGrayscaleUid());
    }

    // ======================== getLatestUpdate ========================

    @Test
    public void getLatestUpdate_delegates() {
        AppUpdate expected = recordOf(INIT_ID, NEW_VERSION, AppUpdateSatusEnum.ALL.getStatus());
        when(appUpdateMapper.selectLatestUpdate("1.2.3", "U_x")).thenReturn(expected);
        assertEquals(expected, appUpdateService.getLatestUpdate("1.2.3", "U_x"));
        verify(appUpdateMapper).selectLatestUpdate("1.2.3", "U_x");
    }
}
