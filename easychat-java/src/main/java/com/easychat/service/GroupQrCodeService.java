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
     * 通过二维码加入群组。
     * <p>
     * 入群行为受群 {@code join_type} 管辖：0 直接加入 / 1 落申请单待群主或管理员审批。
     * 修复前本方法直接 addContact 入群，完全绕过 join_type（群主所设权限形同虚设）。
     *
     * @param tokenUserInfo 当前用户
     * @param qrCodeToken 二维码 token
     * @return {@link com.easychat.entity.enums.JoinTypeEnum} 的 type：0=已直接加入，1=已提交申请待审批
     * @since 2026-10-02 群入群审批闭环（openspec/specs/group-join-approval）
     */
    Integer joinByQrCode(TokenUserInfoDto tokenUserInfo, String qrCodeToken);
}
