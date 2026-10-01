package com.easychat.service;

import com.easychat.entity.po.UserStatus;

/**
 * 用户状态 业务接口
 */
public interface UserStatusService {

    /**
     * 设置状态
     */
    void setStatus(String userId, String content, String imageUrl);

    /**
     * 获取状态
     */
    UserStatus getStatus(String userId);

    /**
     * 清除状态
     */
    void clearStatus(String userId);
}
