package com.easychat.service;

import com.easychat.entity.dto.TokenUserInfoDto;

/**
 * 群二维码业务接口
 */
public interface GroupQrCodeService {

    /**
     * 生成群二维码
     *
     * @param tokenUserInfo 当前用户
     * @param groupId 群组 ID
     * @return 二维码 token
     */
    String generateQrCode(TokenUserInfoDto tokenUserInfo, String groupId);

    /**
     * 通过二维码加入群组
     *
     * @param tokenUserInfo 当前用户
     * @param qrCodeToken 二维码 token
     */
    void joinByQrCode(TokenUserInfoDto tokenUserInfo, String qrCodeToken);
}
