package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.JoinTypeEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.GroupInfoQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.UserContactApplyService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 两个入群端点的出参契约验证。
 *
 * <p>契约变更（openspec/specs/group-join-approval）：
 * {@code POST /api/group/qrCode/join} 与 {@code POST /api/group/invite/join}
 * 出参由 {@code Result<Void>} 改为 {@code Result<Integer>}，
 * 返回 {@link JoinTypeEnum} 的 type：0=已直接加入，1=已提交申请待审批。
 * 前端据此区分「已加入该群聊」与「已提交入群申请」两种提示。
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupJoinJoinTypeContractTest {

    private static final String GROUP_ID = "G_3001";
    private static final String TOKEN = "tok_3001";

    @InjectMocks
    private GroupQrCodeServiceImpl groupQrCodeService;

    @Mock
    private RedisComponet redisComponet;

    @Mock
    private GroupInfoMapper<GroupInfo, GroupInfoQuery> groupInfoMapper;

    @Mock
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Mock
    private UserContactApplyService userContactApplyService;

    @Test
    public void joinByQrCode_returnsZero_whenJoinTypeAllowsDirectEntry() {
        stubGroup();
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                anyString(), anyString())).thenReturn(JoinTypeEnum.JOIN.getType());

        Integer joinType = groupQrCodeService.joinByQrCode(tokenUser("U_a"), TOKEN);

        assertEquals("join_type=0 应返回 0（已直接加入）", Integer.valueOf(0), joinType);
    }

    @Test
    public void joinByQrCode_returnsOne_whenJoinTypeRequiresApproval() {
        stubGroup();
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                anyString(), anyString())).thenReturn(JoinTypeEnum.APPLY.getType());

        Integer joinType = groupQrCodeService.joinByQrCode(tokenUser("U_b"), TOKEN);

        assertEquals("join_type=1 应返回 1（已提交申请待审批）", Integer.valueOf(1), joinType);
    }

    @Test
    public void joinByInvite_returnsOne_whenJoinTypeRequiresApproval() {
        GroupInviteServiceImpl inviteService = new GroupInviteServiceImpl();
        inject(inviteService, "redisComponet", redisComponet);
        inject(inviteService, "groupInfoMapper", groupInfoMapper);
        inject(inviteService, "userContactMapper", userContactMapper);
        inject(inviteService, "userContactApplyService", userContactApplyService);
        stubGroup();
        when(userContactApplyService.applyAdd(any(TokenUserInfoDto.class), eq(GROUP_ID),
                anyString(), anyString())).thenReturn(JoinTypeEnum.APPLY.getType());

        Integer joinType = inviteService.joinByInvite(tokenUser("U_c"), TOKEN);

        assertEquals("邀请路径同样受 join_type 约束，应返回 1", Integer.valueOf(1), joinType);
    }

    private void stubGroup() {
        when(redisComponet.getGroupIdByToken(TOKEN)).thenReturn(GROUP_ID);
        GroupInfo groupInfo = new GroupInfo();
        groupInfo.setGroupId(GROUP_ID);
        groupInfo.setGroupOwnerId("U_owner");
        when(groupInfoMapper.selectByGroupId(GROUP_ID)).thenReturn(groupInfo);
    }

    private void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入测试依赖失败: " + field, e);
        }
    }

    private TokenUserInfoDto tokenUser(String userId) {
        TokenUserInfoDto dto = new TokenUserInfoDto();
        dto.setUserId(userId);
        dto.setNickName("测试用户");
        return dto;
    }
}
