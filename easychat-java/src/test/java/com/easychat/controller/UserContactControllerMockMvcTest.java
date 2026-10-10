package com.easychat.controller;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.PageSize;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.UserContactApplyQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.redis.RedisUtils;
import com.easychat.service.GroupInfoService;
import com.easychat.service.UserContactApplyService;
import com.easychat.service.UserContactService;
import com.easychat.service.UserInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UserContactController} 的**行为**测试（MockMvc 真实路由）。
 *
 * <p>被测点（本控制器真正的风险都在「悄悄改错但仍返回成功」上）：
 * <ol>
 *   <li><b>删好友与拉黑走的是同一个 {@code removeUserContact} 方法、只差 status 参数</b>
 *       —— 两者写反不会编译报错、不会抛异常，只会让「删除」变成「拉黑」，是最典型的静默缺陷</li>
 *   <li>{@code loadContact} 的 contactType 守卫：未知类型 → 1001 且不查库</li>
 *   <li>{@code loadContact} 的查询形态：USER 与 GROUP 分支差异
 *       （USER 只开 queryContactUserInfo；GROUP 开 queryGroupInfo + <b>排除我自己建的群</b>）</li>
 *   <li>{@code loadContact} 的 status 白名单三项：好友 / 被删 / 被拉黑
 *       （<b>不含</b>「我拉黑的」与「拉黑我的」——黑名单另有独立入口）</li>
 *   <li>{@code getContactUserInfo} 的关系守卫：非好友 → 1001 且<b>不查用户信息</b>；
 *       通过时把 remark / groupName 带回</li>
 *   <li>{@code loadApply} 用 currentUserId 而非 receiveUserId（申请单接收方恒为群主），
 *       页长固定 15、排序固定</li>
 *   <li>身份一律取自 token 会话</li>
 * </ol>
 */
class UserContactControllerMockMvcTest {

    private static final String TOKEN = "tok-abc";
    private static final String USER_ID = "U001";

    @Mock
    private UserInfoService userInfoService;
    @Mock
    private GroupInfoService groupInfoService;
    @Mock
    private UserContactService userContactService;
    @Mock
    private UserContactApplyService userContactApplyService;
    @Mock
    private RedisUtils redisUtils;

    @InjectMocks
    private UserContactController controller;

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
        when(redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + TOKEN)).thenReturn(session);
        when(userContactService.findListByParam(any())).thenReturn(Collections.emptyList());
    }

    // ==================== 删除 vs 拉黑（最容易写反的一对） ====================

    @Test
    @DisplayName("delContact → status=DEL（不是 BLACKLIST，写反即「删除变拉黑」）")
    void delContactUsesDelStatus() throws Exception {
        mockMvc.perform(post("/contact/delContact").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userContactService).removeUserContact(USER_ID, "U010", UserContactStatusEnum.DEL);
    }

    @Test
    @DisplayName("addContact2BlackList → status=BLACKLIST（不是 DEL）")
    void addToBlackListUsesBlacklistStatus() throws Exception {
        mockMvc.perform(post("/contact/addContact2BlackList").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(userContactService).removeUserContact(USER_ID, "U010", UserContactStatusEnum.BLACKLIST);
    }

    @Test
    @DisplayName("removeBlackList → 走独立的 removeBlackList（不是 removeUserContact）")
    void removeBlackListUsesDedicatedApi() throws Exception {
        mockMvc.perform(post("/contact/removeBlackList").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isOk());

        verify(userContactService).removeBlackList(USER_ID, "U010");
        verify(userContactService, never()).removeUserContact(anyString(), anyString(), any());
    }

    // ==================== loadContact 查询形态 ====================

    @Test
    @DisplayName("loadContact：未知 contactType → 1001 且不查库")
    void loadContactRejectsUnknownType() throws Exception {
        mockMvc.perform(post("/contact/loadContact").header("token", TOKEN).param("contactType", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verify(userContactService, never()).findListByParam(any());
    }

    @Test
    @DisplayName("loadContact：contactType 收的是枚举常量名（前端传 USER/GROUP），且大小写容忍")
    void loadContactAcceptsEnumNameCaseInsensitively() throws Exception {
        // UserContactTypeEnum.getByName 走 valueOf(name.toUpperCase())，前端传的正是 'USER'
        mockMvc.perform(post("/contact/loadContact").header("token", TOKEN).param("contactType", "user"))
                .andExpect(status().isOk());
        // 枚举的 prefix 字段（U/G）不是合法入参，传了必须被拒
        mockMvc.perform(post("/contact/loadContact").header("token", TOKEN).param("contactType", "U"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("loadContact(USER)：只开联系人信息关联，不排除我建的群")
    void loadContactUserQueryShape() throws Exception {
        mockMvc.perform(post("/contact/loadContact").header("token", TOKEN).param("contactType", "USER"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserContactQuery> captor = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactService).findListByParam(captor.capture());
        UserContactQuery q = captor.getValue();
        assertEquals(USER_ID, q.getUserId());
        assertEquals(Integer.valueOf(0), q.getContactType());
        // 三个开关都是可空 Boolean（未设置分支为 null，供 Mapper 的 <if test="!= null"> 判断）
        assertEquals(Boolean.TRUE, q.getQueryContactUserInfo());
        assertNull(q.getQueryGroupInfo());
        assertNull(q.getExcludeMyGroup());
    }

    @Test
    @DisplayName("loadContact(GROUP)：开群信息关联并排除我自己建的群")
    void loadContactGroupQueryShape() throws Exception {
        mockMvc.perform(post("/contact/loadContact").header("token", TOKEN).param("contactType", "GROUP"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserContactQuery> captor = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactService).findListByParam(captor.capture());
        UserContactQuery q = captor.getValue();
        assertEquals(Integer.valueOf(1), q.getContactType());
        assertEquals(Boolean.TRUE, q.getQueryGroupInfo());
        assertEquals(Boolean.TRUE, q.getExcludeMyGroup(), "群列表必须排除我自己创建的群");
        assertNull(q.getQueryContactUserInfo());
    }

    @Test
    @DisplayName("loadContact：status 白名单三项（好友/被删/被拉黑），不含我拉黑的")
    void loadContactStatusWhitelist() throws Exception {
        mockMvc.perform(post("/contact/loadContact").header("token", TOKEN).param("contactType", "USER"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserContactQuery> captor = ArgumentCaptor.forClass(UserContactQuery.class);
        verify(userContactService).findListByParam(captor.capture());
        Integer[] statuses = captor.getValue().getStatusArray();
        assertEquals(3, statuses.length);
        List<Integer> list = java.util.Arrays.asList(statuses);
        assertTrue(list.contains(UserContactStatusEnum.FRIEND.getStatus()));
        assertTrue(list.contains(UserContactStatusEnum.DEL_BE.getStatus()));
        assertTrue(list.contains(UserContactStatusEnum.BLACKLIST_BE.getStatus()));
        // 我拉黑的（4）走 loadBlackList 独立入口，不进主列表
        assertFalse(list.contains(UserContactStatusEnum.BLACKLIST.getStatus()));
    }

    // ==================== 好友关系守卫 ====================

    @Test
    @DisplayName("getContactUserInfo：无关系行 → 1001 且不查用户信息（避免任意用户信息可查）")
    void getContactUserInfoRejectsNonFriend() throws Exception {
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, "U010")).thenReturn(null);

        mockMvc.perform(post("/contact/getContactUserInfo").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        verify(userInfoService, never()).getUserInfoByUserId(anyString());
    }

    @Test
    @DisplayName("getContactUserInfo：关系状态不在白名单（已被我删除 DEL=2）→ 1001")
    void getContactUserInfoRejectsDeletedRelation() throws Exception {
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, "U010"))
                .thenReturn(relationWith(UserContactStatusEnum.DEL));

        mockMvc.perform(post("/contact/getContactUserInfo").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verify(userInfoService, never()).getUserInfoByUserId(anyString());
    }

    @Test
    @DisplayName("getContactUserInfo：好友 → 带回 remark 与 groupName（详情页要展示可编辑）")
    void getContactUserInfoReturnsRemarkAndGroup() throws Exception {
        UserContact relation = relationWith(UserContactStatusEnum.FRIEND);
        relation.setRemark("强哥");
        relation.setGroupName("同事");
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, "U010")).thenReturn(relation);
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U010");
        userInfo.setNickName("阿强");
        when(userInfoService.getUserInfoByUserId("U010")).thenReturn(userInfo);

        mockMvc.perform(post("/contact/getContactUserInfo").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nickName").value("阿强"))
                .andExpect(jsonPath("$.data.remark").value("强哥"))
                .andExpect(jsonPath("$.data.groupName").value("同事"));
    }

    @Test
    @DisplayName("getContactInfo：无关系行时 contactStatus 为非好友(0)，有则用实际状态覆盖")
    void getContactInfoContactStatusFallback() throws Exception {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("U010");
        when(userInfoService.getUserInfoByUserId("U010")).thenReturn(userInfo);

        // 无关系行
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, "U010")).thenReturn(null);
        mockMvc.perform(post("/contact/getContactInfo").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contactStatus").value(UserContactStatusEnum.NOT_FRIEND.getStatus()));

        // 有关系行（被拉黑）
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, "U010"))
                .thenReturn(relationWith(UserContactStatusEnum.BLACKLIST_BE));
        mockMvc.perform(post("/contact/getContactInfo").header("token", TOKEN).param("contactId", "U010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contactStatus").value(UserContactStatusEnum.BLACKLIST_BE.getStatus()));
    }

    // ==================== 申请单 ====================

    @Test
    @DisplayName("loadApply：用 currentUserId 而非 receiveUserId（否则群管理员看不到群入群申请）")
    void loadApplyUsesCurrentUserId() throws Exception {
        mockMvc.perform(post("/contact/loadApply").header("token", TOKEN).param("pageNo", "2"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserContactApplyQuery> captor = ArgumentCaptor.forClass(UserContactApplyQuery.class);
        verify(userContactApplyService).findListByPage(captor.capture());
        UserContactApplyQuery q = captor.getValue();
        assertEquals(USER_ID, q.getCurrentUserId());
        assertEquals(Integer.valueOf(2), q.getPageNo());
        assertEquals(PageSize.SIZE15.getSize(), q.getPageSize());
        assertTrue(q.getQueryContactInfo());
        assertEquals(SortOption.USER_CONTACT_APPLY_LAST_APPLY_TIME_DESC, q.getSortOption());
    }

    @Test
    @DisplayName("dealWithApply：只传操作者本人 id，不得由参数指定审批人")
    void dealWithApplyUsesSessionIdentity() throws Exception {
        mockMvc.perform(post("/contact/dealWithApply")
                        .header("token", TOKEN)
                        .param("applyId", "77")
                        .param("status", "1")
                        .param("userId", "U999"))
                .andExpect(status().isOk());

        verify(userContactApplyService).dealWithApply(USER_ID, 77, 1);
    }

    @Test
    @DisplayName("applyAdd / setRemark / setGroup / nudge / loadBlackList：身份一律取自会话")
    void identityAlwaysComesFromSession() throws Exception {
        mockMvc.perform(post("/contact/applyAdd")
                .header("token", TOKEN).param("contactId", "U010").param("contactType", "U")).andExpect(status().isOk());
        mockMvc.perform(post("/contact/setRemark")
                .header("token", TOKEN).param("contactId", "U010").param("remark", "强哥")).andExpect(status().isOk());
        mockMvc.perform(post("/contact/setGroup")
                .header("token", TOKEN).param("contactId", "U010").param("groupName", "同事")).andExpect(status().isOk());
        mockMvc.perform(post("/contact/nudge")
                .header("token", TOKEN).param("contactId", "U010").param("suffix", "的头")).andExpect(status().isOk());
        mockMvc.perform(post("/contact/loadBlackList").header("token", TOKEN)).andExpect(status().isOk());

        verify(userContactApplyService).applyAdd(any(), eq("U010"), eq("U"), eq(null));
        verify(userContactService).setContactRemark(USER_ID, "U010", "强哥");
        verify(userContactService).setContactGroup(USER_ID, "U010", "同事");
        verify(userContactService).sendNudge(USER_ID, "U010", "的头");
        verify(userContactService).loadBlackList(USER_ID);
    }

    @Test
    @DisplayName("黑名单为空返回空数组而非 null")
    void loadBlackListEmptyIsArray() throws Exception {
        when(userContactService.loadBlackList(USER_ID)).thenReturn(Collections.emptyList());

        mockMvc.perform(post("/contact/loadBlackList").header("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private UserContact relationWith(UserContactStatusEnum status) {
        UserContact c = new UserContact();
        c.setUserId(USER_ID);
        c.setContactId("U010");
        c.setStatus(status.getStatus());
        return c;
    }
}
