# Design — 操作日志

- 关联 Proposal: 2026-09-30-operation-log/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `OperationLog` | 新增实体 | 1:1 对应表结构 |
| `OperationLogMapper` | 新增 Mapper | 只写 SQL |
| `OperationLogService` | 新增 Service | 业务规则 |
| `UserInfoServiceImpl` | 登录/改密时记录日志 | 业务逻辑 |
| `ChatMessageServiceImpl` | 删消息时记录日志 | 业务逻辑 |
| `GroupInfoServiceImpl` | 强制下线时记录日志 | 业务逻辑 |

## 2. 接口设计

无新增接口。

## 3. 数据模型

| 表 | 变更 | 字段 | 类型 | 说明 |
|----|------|------|------|------|
| `operation_log` | 新增 | `id` | BIGINT | 自增 ID |
| | | `user_id` | VARCHAR(12) | 操作用户 ID |
| | | `operation_type` | VARCHAR(20) | 操作类型 |
| | | `operation_desc` | VARCHAR(200) | 操作描述 |
| | | `ip_address` | VARCHAR(50) | IP 地址 |
| | | `create_time` | BIGINT | 操作时间毫秒 |

> 变更需同步 `easychat.sql`。

## 4. 安全设计

- 鉴权: 无新增接口
- 数据权限: 无影响
- 输入校验: 无
- SQL 注入防护: 使用 `#{}`

## 5. ADR

### ADR-001: 操作日志存储位置

- 状态: 已接受
- 上下文: 操作日志需要持久化存储
- 决策: 存储在 MySQL 数据库
- 后果: 会增加数据库写入量，但便于查询和审计

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 日志写入失败影响主流程 | 低 | 中 | 异步写入，失败不影响主流程 |
| 日志表数据量过大 | 中 | 低 | 定期归档/清理 |

## 7. 依赖与前提

- 需要数据库连接
