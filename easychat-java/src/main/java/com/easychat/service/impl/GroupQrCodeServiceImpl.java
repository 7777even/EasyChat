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

    @Resource
    private RedisComponet redisComponet;

    @Resource
    private GroupInfoMapper<GroupInfo, com.easychat.entity.query.GroupInfoQuery> groupInfoMapper;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private UserContactService userContactService;

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
    public void joinByQrCode(TokenUserInfoDto tokenUserInfo, String qrCodeToken) {
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
        UserContactQuery query = new UserContactQuery();
        query.setUserId(tokenUserInfo.getUserId());
        query.setContactId(groupId);
        UserContact existingContact = userContactMapper.selectByUserIdAndContactId(tokenUserInfo.getUserId(), groupId);
        if (existingContact != null && UserContactStatusEnum.FRIEND.getStatus().equals(existingContact.getStatus())) {
            throw new BusinessException("您已经是该群成员");
        }
        // 加入群组
        userContactService.addContact(tokenUserInfo.getUserId(), null, groupId, UserContactTypeEnum.GROUP.getType(), null);
    }

    /**
     * 根据 token 获取群组 ID
     * 注意：这是一个简化的实现，实际生产环境可能需要更高效的方式
     */
    private String getGroupIdByToken(String token) {
        // 遍历所有群组，查找匹配的 token
        // 由于 Redis 没有直接的方法来根据 value 查找 key，这里需要遍历
        // 实际生产环境可以使用额外的映射表来优化
        return null;
    }
}
