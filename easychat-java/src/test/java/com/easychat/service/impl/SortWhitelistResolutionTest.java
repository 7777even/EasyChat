package com.easychat.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoJUnitRunner.Silent;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserInfoBeautyQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserInfoBeautyMapper;

/**
 * 管理端列表端点的排序白名单解析（ADR-001 + ADR-004）。
 *
 * <p>背景：修复前 {@code AdminGroupController#loadGroup} 与
 * {@code AdminUserInfoBeautyController#loadBeautyAccountList} 直接绑定 {@code *Query}，
 * Controller 与 Service 均未设排序，而 Mapper 用 {@code order by ${query.orderBy}} 拼接
 * → 调用方（管理员）可把任意字符串送进 SQL。
 *
 * <p>本类锁定：
 * <ol>
 *   <li>白名单内取值 → 解析为对应枚举项，并**真的传到 Mapper**（ArgumentCaptor 实证，
 *       而非只看 Service 返回值）；</li>
 *   <li>白名单外取值 → 抛 {@code CODE_1001}，**且不落到 Mapper**（不做「静默回退」），
 *       符合 ADR-004；</li>
 *   <li>未指定排序 → 取该表默认项，**保证分页恒有 ORDER BY**（C4，
 *       修复前 orderBy 为空时这两个端点完全没有排序，翻页可能重复/漏行）。</li>
 * </ol>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SortWhitelistResolutionTest {

    @Mock
    private GroupInfoMapper<com.easychat.entity.po.GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserInfoBeautyMapper<com.easychat.entity.po.UserInfoBeauty, UserInfoBeautyQuery> userInfoBeautyMapper;

    private GroupInfoServiceImpl groupInfoService;
    private UserInfoBeautyServiceImpl userInfoBeautyService;

    @Before
    public void setUp() {
        groupInfoService = new GroupInfoServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(groupInfoService, "groupInfoMapper", groupInfoMapper);
        userInfoBeautyService = new UserInfoBeautyServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(userInfoBeautyService, "userInfoBeautyMapper", userInfoBeautyMapper);
    }

    // ── 1. 白名单内取值能落到 Mapper ────────────────────────────

    @Test
    public void groupList_whitelistedSort_reachesMapper() {
        when(groupInfoMapper.selectCount(any(GroupInfoQuery.class))).thenReturn(0);
        when(groupInfoMapper.selectList(any(GroupInfoQuery.class))).thenReturn(new ArrayList<>());

        GroupInfoQuery q = new GroupInfoQuery();
        q.setSortField("createTime");
        q.setSortDirection("asc");
        groupInfoService.findListByPage(q);

        ArgumentCaptor<GroupInfoQuery> captor = ArgumentCaptor.forClass(GroupInfoQuery.class);
        verify(groupInfoMapper, times(1)).selectList(captor.capture());
        assertEquals("合法排序必须传到 Mapper（否则 XML 的 choose 分支无从选择）",
            SortOption.GROUP_INFO_CREATE_TIME_ASC, captor.getValue().getSortOption());
    }

    @Test
    public void beautyList_whitelistedSort_reachesMapper() {
        when(userInfoBeautyMapper.selectCount(any(UserInfoBeautyQuery.class))).thenReturn(0);
        when(userInfoBeautyMapper.selectList(any(UserInfoBeautyQuery.class))).thenReturn(new ArrayList<>());

        UserInfoBeautyQuery q = new UserInfoBeautyQuery();
        q.setSortField("id");
        q.setSortDirection("asc");
        userInfoBeautyService.findListByPage(q);

        ArgumentCaptor<UserInfoBeautyQuery> captor = ArgumentCaptor.forClass(UserInfoBeautyQuery.class);
        verify(userInfoBeautyMapper, times(1)).selectList(captor.capture());
        assertEquals(SortOption.USER_INFO_BEAUTY_ID_ASC, captor.getValue().getSortOption());
    }

    // ── 2. 白名单外取值必须报错且不落到 Mapper（ADR-004）──────────

    @Test
    public void groupList_injectionPayloadAsSortField_throwsAndNeverHitsMapper() {
        GroupInfoQuery q = new GroupInfoQuery();
        q.setSortField("(select 1 from information_schema.tables)");
        try {
            groupInfoService.findListByPage(q);
            fail("注入载荷作为 sortField 必须抛 CODE_1001，实际未抛");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(groupInfoMapper, times(0)).selectList(any(GroupInfoQuery.class));
    }

    @Test
    public void groupList_injectionPayloadAsSortDirection_throws() {
        GroupInfoQuery q = new GroupInfoQuery();
        q.setSortField("createTime");
        q.setSortDirection("desc; drop table user_info");
        try {
            groupInfoService.findListByPage(q);
            fail("注入载荷作为 sortDirection 必须抛 CODE_1001，实际未抛");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(groupInfoMapper, times(0)).selectList(any(GroupInfoQuery.class));
    }

    @Test
    public void beautyList_injectionPayloadAsSortField_throws() {
        UserInfoBeautyQuery q = new UserInfoBeautyQuery();
        q.setSortField("id desc");
        try {
            userInfoBeautyService.findListByPage(q);
            fail("含空格与方向的 sortField 必须抛 CODE_1001，实际未抛");
        } catch (BusinessException e) {
            assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
        }
        verify(userInfoBeautyMapper, times(0)).selectList(any(UserInfoBeautyQuery.class));
    }

    // ── 3. 未指定排序 → 默认项（修掉「分页无 ORDER BY」）──────────

    @Test
    public void groupList_noSortField_usesTableDefault() {
        when(groupInfoMapper.selectCount(any(GroupInfoQuery.class))).thenReturn(0);
        when(groupInfoMapper.selectList(any(GroupInfoQuery.class))).thenReturn(new ArrayList<>());

        groupInfoService.findListByPage(new GroupInfoQuery());

        ArgumentCaptor<GroupInfoQuery> captor = ArgumentCaptor.forClass(GroupInfoQuery.class);
        verify(groupInfoMapper).selectList(captor.capture());
        SortOption opt = captor.getValue().getSortOption();
        assertNotNull("未指定排序时必须有默认项，否则分页无 ORDER BY（C4）", opt);
        assertEquals(SortOption.GROUP_INFO_CREATE_TIME_DESC, opt);
    }

    @Test
    public void beautyList_noSortField_usesTableDefault() {
        when(userInfoBeautyMapper.selectCount(any(UserInfoBeautyQuery.class))).thenReturn(0);
        when(userInfoBeautyMapper.selectList(any(UserInfoBeautyQuery.class))).thenReturn(new ArrayList<>());

        userInfoBeautyService.findListByPage(new UserInfoBeautyQuery());

        ArgumentCaptor<UserInfoBeautyQuery> captor = ArgumentCaptor.forClass(UserInfoBeautyQuery.class);
        verify(userInfoBeautyMapper).selectList(captor.capture());
        assertEquals(SortOption.USER_INFO_BEAUTY_ID_DESC, captor.getValue().getSortOption());
    }

    // ── 4. 服务端已预设的 sortOption 不被默认值覆盖 ───────────────

    @Test
    public void presetSortOption_isNotOverwrittenByDefault() {
        when(groupInfoMapper.selectCount(any(GroupInfoQuery.class))).thenReturn(0);
        when(groupInfoMapper.selectList(any(GroupInfoQuery.class))).thenReturn(new ArrayList<>());

        GroupInfoQuery q = new GroupInfoQuery();
        // 内部调用方（如 GroupController 的非管理端列表）已显式设定排序
        q.setSortOption(SortOption.GROUP_INFO_CREATE_TIME_DESC);
        groupInfoService.findListByPage(q);

        ArgumentCaptor<GroupInfoQuery> captor = ArgumentCaptor.forClass(GroupInfoQuery.class);
        verify(groupInfoMapper).selectList(captor.capture());
        assertEquals(SortOption.GROUP_INFO_CREATE_TIME_DESC, captor.getValue().getSortOption());
    }
}
