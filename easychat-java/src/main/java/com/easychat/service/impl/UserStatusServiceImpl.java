package com.easychat.service.impl;

import com.easychat.entity.po.UserStatus;
import com.easychat.mappers.UserStatusMapper;
import com.easychat.service.UserStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 用户状态 业务接口实现
 */
@Service("userStatusService")
public class UserStatusServiceImpl implements UserStatusService {

    @Resource
    private UserStatusMapper<UserStatus, ?> userStatusMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setStatus(String userId, String content, String imageUrl) {
        // 删除旧状态
        userStatusMapper.deleteByUserId(userId);

        // 插入新状态
        UserStatus userStatus = new UserStatus();
        userStatus.setUserId(userId);
        userStatus.setContent(content);
        userStatus.setImageUrl(imageUrl);
        long now = System.currentTimeMillis();
        userStatus.setCreateTime(now);
        userStatus.setExpireTime(now + 24 * 60 * 60 * 1000L); // 24小时后过期
        userStatusMapper.insert(userStatus);
    }

    @Override
    public UserStatus getStatus(String userId) {
        UserStatus userStatus = userStatusMapper.selectByUserId(userId);
        // 检查是否过期
        if (userStatus != null && userStatus.getExpireTime() != null
                && userStatus.getExpireTime() < System.currentTimeMillis()) {
            // 已过期，删除并返回 null
            userStatusMapper.deleteByUserId(userId);
            return null;
        }
        return userStatus;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearStatus(String userId) {
        userStatusMapper.deleteByUserId(userId);
    }
}
