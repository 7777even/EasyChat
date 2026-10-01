package com.easychat.service.impl;

import com.easychat.entity.po.OperationLog;
import com.easychat.mappers.OperationLogMapper;
import com.easychat.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 操作日志业务实现
 */
@Service("operationLogService")
public class OperationLogServiceImpl implements OperationLogService {

    private static final Logger logger = LoggerFactory.getLogger(OperationLogServiceImpl.class);

    @Resource
    private OperationLogMapper<OperationLog, com.easychat.entity.query.OperationLogQuery> operationLogMapper;

    @Override
    public void recordLog(String userId, String operationType, String operationDesc, String ipAddress) {
        try {
            OperationLog log = new OperationLog();
            log.setUserId(userId);
            log.setOperationType(operationType);
            log.setOperationDesc(operationDesc);
            log.setIpAddress(ipAddress);
            log.setCreateTime(System.currentTimeMillis());
            operationLogMapper.insert(log);
        } catch (Exception e) {
            // 日志写入失败不影响主流程
            logger.error("操作日志写入失败: userId={}, type={}", userId, operationType, e);
        }
    }
}
