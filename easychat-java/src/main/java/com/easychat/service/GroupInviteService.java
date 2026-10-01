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
     * 通过邀请链接加入群组
     *
     * @param tokenUserInfo 当前用户
     * @param inviteToken 邀请链接 token
     */
    void joinByInvite(TokenUserInfoDto tokenUserInfo, String inviteToken);
}
