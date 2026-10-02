package com.easychat.service.impl;

import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.GroupMemberRoleEnum;
import com.easychat.entity.enums.UserContactStatusEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.entity.po.GroupInfo;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.GroupInfoMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.GroupQrCodeService;
import com.easychat.service.UserContactApplyService;
import com.easychat.service.UserContactService;
import com.easychat.utils.StringTools;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Date;
import java.util.UUID;

/**
 * 群二维码业务实现
 */
@Service("groupQrCodeService")
public class GroupQrCodeServiceImpl implements GroupQrCodeService {

    /** 扫码入群的申请附言（审批人在申请列表上据此识别来源） */
    private static final String APPLY_INFO_FROM_QRCODE = "通过群二维码申请加入";

    @Resource
    private RedisComponet redisComponet;

    @Resource
    private GroupInfoMapper<GroupInfo, com.easychat.entity.query.GroupInfoQuery> groupInfoMapper;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private UserContactService userContactService;

    @Resource
    private UserContactApplyService userContactApplyService;

    @Override
    public String generateQrCode(TokenUserInfoDto tokenUserInfo, String groupId) {
        // 校验群组是否存在
        GroupInfo groupInfo = groupInfoMapper.selectByGroupId(groupId);
        if (groupInfo == null) {
            throw new BusinessException("群组不存在");
        }
        // 校验是否是群主/管理员
        if (!groupInfo.getGroupOwnerId().equals(tokenUserInfo.getUserId())) {
            UserContactQuery query = new UserContactQuery();
            query.setUserId(tokenUserInfo.getUserId());
            query.setContactId(groupId);
            UserContact userContact = userContactMapper.selectByUserIdAndContactId(tokenUserInfo.getUserId(), groupId);
            if (userContact == null || !GroupMemberRoleEnum.ADMIN.getRole().equals(userContact.getRole())) {
                throw new BusinessException("只有群主/管理员可以生成群二维码");
            }
        }
        // 生成 token
        String token = UUID.randomUUID().toString().replace("-", "");
        // 保存到 Redis
        redisComponet.saveGroupQrCode(groupId, token);
        return token;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer joinByQrCode(TokenUserInfoDto tokenUserInfo, String qrCodeToken) {
        // 根据 token 获取群组 ID
        String groupId = getGroupIdByToken(qrCodeToken);
        if (StringTools.isEmpty(groupId)) {
            throw new BusinessException("二维码已过期或无效");
        }
        // 校验群组是否存在
        GroupInfo groupInfo = groupInfoMapper.selectByGroupId(groupId);
        if (groupInfo == null) {
            throw new BusinessException("群组不存在");
        }
        // 校验是否已经是群成员
        UserContact existingContact = userContactMapper.selectByUserIdAndContactId(tokenUserInfo.getUserId(), groupId);
        if (existingContact != null && UserContactStatusEnum.FRIEND.getStatus().equals(existingContact.getStatus())) {
            throw new BusinessException("您已经是该群成员");
        }
        // 委托既有申请链路：入群行为受 group_info.join_type 管辖
        // （0 直接加入 / 1 落申请单待群主或管理员审批），此处不得自行入群
        return userContactApplyService.applyAdd(tokenUserInfo, groupId,
                UserContactTypeEnum.GROUP.name(), APPLY_INFO_FROM_QRCODE);
    }

    /**
     * 根据 token 获取群组 ID
     * <p>
     * 反查索引由 {@code RedisComponet.saveGroupQrCode} 在生成 token 时同步写入
     * （{@code easychat:group:qrcode:token:{token}} → groupId，同 TTL 7 天）。
     * 原实现为 {@code return null} 桩，导致 join 接口永远报「二维码已过期或无效」。
     */
    private String getGroupIdByToken(String token) {
        return redisComponet.getGroupIdByToken(token);
    }
}
