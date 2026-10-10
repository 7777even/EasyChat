package com.easychat.controller;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.vo.Result;
import com.easychat.redis.RedisUtils;
import com.easychat.service.GroupFileService;
import com.easychat.service.GroupInfoService;
import com.easychat.service.GroupInviteService;
import com.easychat.service.GroupQrCodeService;
import com.easychat.service.UserContactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 群域控制器（{@link GroupController} 的群详情守卫 + {@link GroupFileController}）的行为测试。
 *
 * <p>本组真正的业务逻辑集中在 {@code getGroupDetailCommon}：
 * <b>必须同时满足「我是群成员(好友态)」与「群未解散」</b>，否则拒绝。
 * 它是群信息泄露的唯一入口守卫，故重点覆盖其四条分支；而群管理类端点
 * （设管理员/禁言/转让/退群/解散）本身只是委托，角色与权限校验全在 Service 层
 * （{@code GroupInfoServiceImplTest} 已覆盖 42 例），此处只锁「身份来自会话 + 参数透传」。
 */
class GroupDomainControllersMockMvcTest {

    private static final String TOKEN = MockMvcSupport.TOKEN;
    private static final String USER_ID = MockMvcSupport.USER_ID;
    private static final String GROUP_ID = "G001";

    @Mock
    private GroupInfoService groupInfoService;
    @Mock
    private GroupQrCodeService groupQrCodeService;
    @Mock
    private GroupInviteService groupInviteService;
    @Mock
    private GroupFileService groupFileService;
    @Mock
    private UserContactService userContactService;
    @Mock
    private RedisUtils redisUtils;

    private MockMvc groupMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        MockMvcSupport.stubSession(redisUtils, MockMvcSupport.sessionUser());

        GroupController groupController = new GroupController();
        inject(groupController, "groupInfoService", groupInfoService);
        inject(groupController, "userContactService", userContactService);
        inject(groupController, "groupQrCodeService", groupQrCodeService);
        inject(groupController, "groupInviteService", groupInviteService);
        inject(groupController, "redisUtils", redisUtils);

        GroupFileController fileController = new GroupFileController();
        inject(fileController, "groupFileService", groupFileService);
        inject(fileController, "redisUtils", redisUtils);

        MockMvc fileMvc = MockMvcBuilders.standaloneSetup(fileController)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
        // 两个控制器不能挂在同一个 MockMvc 上，分别断言；此处只保留 group，文件侧单独构建
        this.groupMvc = MockMvcBuilders.standaloneSetup(groupController)
                .setControllerAdvice(new AGlobalExceptionHandlerController())
                .build();
        this.fileMvcHolder = fileMvc;
    }

    private MockMvc fileMvcHolder;

    private MockMvc fileMvc() {
        return fileMvcHolder;
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

    private void givenMembership(int status) {
        UserContact c = new UserContact();
        c.setUserId(USER_ID);
        c.setContactId(GROUP_ID);
        c.setStatus(status);
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, GROUP_ID)).thenReturn(c);
    }

    private GroupInfo normalGroup() {
        GroupInfo g = new GroupInfo();
        g.setGroupId(GROUP_ID);
        g.setGroupName("测试群");
        // ⚠ GroupStatusEnum: NORMAL=1、DISSOLUTION=0（两个值相反，写反会让「正常群」被判成已解散）
        g.setStatus(com.easychat.entity.enums.GroupStatusEnum.NORMAL.getStatus());
        return g;
    }

    // ==================== 群详情守卫 ====================

    @Test
    @DisplayName("群详情：非成员（无关系行）→ 拒绝且不查群（否则任何群信息可被枚举）")
    void groupInfoRejectsNonMember() throws Exception {
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, GROUP_ID)).thenReturn(null);

        groupMvc.perform(post("/group/getGroupInfo").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        verify(groupInfoService, never()).getGroupInfoByGroupId(anyString());
    }

    @Test
    @DisplayName("群详情：关系行存在但非好友态（如已被移出）→ 同样拒绝")
    void groupInfoRejectsNonFriendStatus() throws Exception {
        givenMembership(UserContactStatusEnum.DEL_BE.getStatus());

        groupMvc.perform(post("/group/getGroupInfo").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verify(groupInfoService, never()).getGroupInfoByGroupId(anyString());
    }

    @Test
    @DisplayName("群详情：群已解散 → 拒绝（成员关系还在也不能读到已解散群）")
    void groupInfoRejectsDissolvedGroup() throws Exception {
        givenMembership(UserContactStatusEnum.FRIEND.getStatus());
        GroupInfo dissolved = normalGroup();
        dissolved.setStatus(com.easychat.entity.enums.GroupStatusEnum.DISSOLUTION.getStatus());
        when(groupInfoService.getGroupInfoByGroupId(GROUP_ID)).thenReturn(dissolved);

        groupMvc.perform(post("/group/getGroupInfo").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("群详情：成员 + 群正常 → 放行并回填成员数")
    void groupInfoReturnsMemberCount() throws Exception {
        givenMembership(UserContactStatusEnum.FRIEND.getStatus());
        when(groupInfoService.getGroupInfoByGroupId(GROUP_ID)).thenReturn(normalGroup());
        when(userContactService.findCountByParam(any())).thenReturn(7);

        groupMvc.perform(post("/group/getGroupInfo").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groupName").value("测试群"))
                .andExpect(jsonPath("$.data.memberCount").value(7));
    }

    @Test
    @DisplayName("群聊态群详情：同样受成员+未解散守卫约束（不得绕过 getGroupInfo 的校验）")
    void groupInfo4ChatSharesTheSameGuard() throws Exception {
        when(userContactService.getUserContactByUserIdAndContactId(USER_ID, GROUP_ID)).thenReturn(null);

        groupMvc.perform(post("/group/getGroupInfo4Chat").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        verify(groupInfoService, never()).getGroupInfoByGroupId(anyString());
    }

    // ==================== 我��群 ====================

    @Test
    @DisplayName("我的群列表：按「群主是我」过滤 + 只含正常群 + 固定创建时间倒序")
    void loadMyGroupUsesSortWhitelist() throws Exception {
        when(groupInfoService.findListByParam(any())).thenReturn(java.util.Collections.emptyList());

        groupMvc.perform(post("/group/loadMyGroup").header("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        org.mockito.ArgumentCaptor<com.easychat.entity.query.GroupInfoQuery> captor =
                org.mockito.ArgumentCaptor.forClass(com.easychat.entity.query.GroupInfoQuery.class);
        verify(groupInfoService).findListByParam(captor.capture());
        // 注意是 groupOwnerId（我创建的群），不是 createUserId
        assertEquals(USER_ID, captor.getValue().getGroupOwnerId());
        // 已解散的群不得出现在列表里
        assertEquals(com.easychat.entity.enums.GroupStatusEnum.NORMAL.getStatus(),
                captor.getValue().getStatus());
        assertEquals(com.easychat.entity.enums.SortOption.GROUP_INFO_CREATE_TIME_DESC,
                captor.getValue().getSortOption());
    }

    // ==================== 群管理端点：身份与参数透传 ====================

    @Test
    @DisplayName("设管理员 / 禁言 / 转让 / 退群 / 解散：操作者一律取自会话")
    void managementEndpointsUseSessionIdentity() throws Exception {
        groupMvc.perform(post("/group/setAdmin").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("userId", "U010").param("role", "2"))
                .andExpect(status().isOk());
        groupMvc.perform(post("/group/muteMember").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("userId", "U010").param("minutes", "10"))
                .andExpect(status().isOk());
        groupMvc.perform(post("/group/transferOwner").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("newOwnerUserId", "U010"))
                .andExpect(status().isOk());
        groupMvc.perform(post("/group/leaveGroup").header("token", TOKEN)
                        .param("groupId", GROUP_ID)).andExpect(status().isOk());
        groupMvc.perform(post("/group/dissolutionGroup").header("token", TOKEN)
                        .param("groupId", GROUP_ID)).andExpect(status().isOk());

        verify(groupInfoService).setAdmin(any(TokenUserInfoDto.class), eq(GROUP_ID), eq("U010"), any());
        verify(groupInfoService).muteMember(any(TokenUserInfoDto.class), eq(GROUP_ID), eq("U010"), eq(10));
        verify(groupInfoService).transferOwner(any(TokenUserInfoDto.class), eq(GROUP_ID), eq("U010"));
        verify(groupInfoService).leaveGroup(eq(USER_ID), eq(GROUP_ID), any());
        verify(groupInfoService).dissolutionGroup(eq(USER_ID), eq(GROUP_ID));
    }

    @Test
    @DisplayName("群公告编辑：身份取自会话")
    void editNoticeUsesSessionIdentity() throws Exception {
        groupMvc.perform(post("/group/editNotice").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("notice", "本周六开会"))
                .andExpect(status().isOk());
        verify(groupInfoService).editGroupNotice(any(TokenUserInfoDto.class), eq(GROUP_ID), eq("本周六开会"));
    }

    @Test
    @DisplayName("二维码 / 邀请：生成的 token 进包络 data（两者走各自独立的 Service）")
    void generateEndpointsReturnToken() throws Exception {
        when(groupQrCodeService.generateQrCode(any(TokenUserInfoDto.class), eq(GROUP_ID))).thenReturn("qr-token-1");
        when(groupInviteService.generateInvite(any(TokenUserInfoDto.class), eq(GROUP_ID))).thenReturn("invite-token-2");

        groupMvc.perform(post("/group/qrCode/generate").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("qr-token-1"));
        groupMvc.perform(post("/group/invite/generate").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("invite-token-2"));
    }

    @Test
    @DisplayName("扫码 / 邀请入群：返回 joinType 进包络（前端据此决定直接进群还是走申请）")
    void joinEndpointsReturnJoinType() throws Exception {
        when(groupQrCodeService.joinByQrCode(any(TokenUserInfoDto.class), eq("qr-token-1"))).thenReturn(0);
        when(groupInviteService.joinByInvite(any(TokenUserInfoDto.class), eq("invite-token-2"))).thenReturn(1);

        groupMvc.perform(post("/group/qrCode/join").header("token", TOKEN).param("qrCodeToken", "qr-token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        groupMvc.perform(post("/group/invite/join").header("token", TOKEN).param("inviteToken", "invite-token-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("群成员列表：委托 Service（角色可见性由 Service 判定）")
    void memberListDelegates() throws Exception {
        when(groupInfoService.getGroupMemberList(any(TokenUserInfoDto.class), eq(GROUP_ID)))
                .thenReturn(new com.easychat.entity.vo.PaginationResultVO<UserContact>(
                        java.util.Collections.emptyList()));

        groupMvc.perform(post("/group/memberList").header("token", TOKEN).param("groupId", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(groupInfoService).getGroupMemberList(any(TokenUserInfoDto.class), eq(GROUP_ID));
    }

    // ==================== 群文件 ====================

    @Test
    @DisplayName("群文件列表：pageNo/pageSize 原样透传（不得被控制器改写默认值）")
    void groupFileListPassesPaging() throws Exception {
        when(groupFileService.getGroupFileList(any(TokenUserInfoDto.class), eq(GROUP_ID), anyInt(), anyInt()))
                .thenReturn(new com.easychat.entity.vo.PaginationResultVO<com.easychat.entity.vo.GroupFileVO>(
                        java.util.Collections.emptyList()));

        fileMvc().perform(post("/group/file/list").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("pageNo", "3").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(groupFileService).getGroupFileList(any(TokenUserInfoDto.class), eq(GROUP_ID), eq(3), eq(20));
    }

    @Test
    @DisplayName("群文件删除：身份取自会话，fileId 透传")
    void groupFileDeleteUsesSessionIdentity() throws Exception {
        fileMvc().perform(post("/group/file/delete").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("fileId", "99"))
                .andExpect(status().isOk());
        verify(groupFileService).deleteGroupFile(any(TokenUserInfoDto.class), eq(GROUP_ID), eq(99L));
    }

    @Test
    @DisplayName("群文件操作不得用 GET（写操作走 GET 会被预取/爬虫误触发）")
    void groupFileDeleteRejectsGet() throws Exception {
        fileMvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/group/file/delete").header("token", TOKEN)
                        .param("groupId", GROUP_ID).param("fileId", "99"))
                .andExpect(status().isMethodNotAllowed());
        verify(groupFileService, never()).deleteGroupFile(any(), anyString(), anyLong());
    }
}
