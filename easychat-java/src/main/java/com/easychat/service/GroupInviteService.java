package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;

/**
 * 群邀请业务接口
 */
public interface GroupInviteService {

    /**
     * 生成群邀请链接
     *
     * @param tokenUserInfo 当前用户
     * @param groupId 群组 ID
     * @return 邀请链接 token
     */
    String generateInvite(TokenUserInfoDto tokenUserInfo, String groupId);

    /**
     * 通过邀请链接加入群组。
     * <p>
     * 入群行为受群 {@code join_type} 管辖：0 直接加入 / 1 落申请单待群主或管理员审批。
     * 修复前本方法直接 addContact 入群，完全绕过 join_type。
     *
     * @param tokenUserInfo 当前用户
     * @param inviteToken 邀请链接 token
     * @return {@link com.easychat.entity.enums.JoinTypeEnum} 的 type：0=已直接加入，1=已提交申请待审批
     * @since 2026-10-02 群入群审批闭环（openspec/specs/group-join-approval）
     */
    Integer joinByInvite(TokenUserInfoDto tokenUserInfo, String inviteToken);
}
