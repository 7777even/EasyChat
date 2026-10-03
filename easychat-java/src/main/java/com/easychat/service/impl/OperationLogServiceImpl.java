package com.easychat.service.impl;

import com.easychat.entity.po.OperationLog;
import com.easychat.mappers.OperationLogMapper;
import com.easychat.service.OperationLogService;
import com.easychat.utils.IpTools;
import com.easychat.utils.StringTools;
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
            // 2026-10-03：入参为空时自动补齐客户端 IP。
            //
            // 背景：ip_address 列与本方法的 ipAddress 参数早已存在，但此前全仓 6 处调用点
            // 无一例外传 null → 审计日志存在却无法用于审计，且恰恰最需要溯源的
            // LOGIN_FAILED（密码爆破无法定位来源 IP）/ FORCE_OFFLINE / UPDATE_PASSWORD 全无 IP。
            //
            // 为什么在实现内部补而不是改 6 处调用点（design.md ADR-001）：
            // 调用点都是 Service 层，不持有也不应持有 HttpServletRequest（AGENTS §3.4）。
            // 在实现内部补，还带来一个额外好处：**将来新增调用点默认就带 IP**，
            // 不存在「新加一处又忘了传」的漏网。
            //
            // 显式传入非空值时**不覆盖**，保留内部任务等场景显式标注 IP 的能力。
            log.setIpAddress(StringTools.isEmpty(ipAddress) ? IpTools.getClientIp() : ipAddress);
            log.setCreateTime(System.currentTimeMillis());
            operationLogMapper.insert(log);
        } catch (Exception e) {
            // 日志写入失败不影响主流程
            logger.error("操作日志写入失败: userId={}, type={}", userId, operationType, e);
        }
    }
}
