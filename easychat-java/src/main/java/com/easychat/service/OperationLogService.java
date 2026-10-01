package com.easychat.service;

import com.easychat.entity.po.OperationLog;

/**
 * 操作日志业务接口
 */
public interface OperationLogService {

    /**
     * 记录操作日志
     *
     * @param userId 用户 ID
     * @param operationType 操作类型
     * @param operationDesc 操作描述
     * @param ipAddress IP 地址
     */
    void recordLog(String userId, String operationType, String operationDesc, String ipAddress);
}
