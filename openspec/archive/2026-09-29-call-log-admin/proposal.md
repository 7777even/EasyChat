# Proposal — 通话记录管理端列表查询（筛选 + 昵称/群名解析）

- 创建日期: 2026-09-29
- 效率等级: L3（新增只读接口 + 前端页面；无表结构 / 无新错误码 / 无权限语义变更，不命中 §8）

## Why

voice-call Change 已交付 `call_log` 持久化（migration-008，voice-call spec C5），但全仓无任何查询入口——`CallLogService` 仅 `save` + 服务内部 `selectList`，无 Controller 端点、无管理页面。运营无法审计通话记录（谁在何时通话、未接/拒接情况），落库数据等于死资产，「查询可回溯（落库口径）」只兑现了一半。

## What Changes

- 后端:
  - 新增 `AdminCallLogController`（`POST /admin/callLog/loadCallLog`，`@GlobalInterceptor(checkAdmin = true)`）
  - 新增 `AdminCallLogService(+Impl)`：分页 + 筛选，**Service 首行硬编码 `setOrderBy` 覆盖**（`CallLogQuery` 继承 `BaseParam.orderBy` 可被 HTTP 绑定且 XML 用 `${}`，必须服务端覆盖防注入）
  - 新增 `CallLogReadMapper` + XML：count + 分页列表，LEFT JOIN `user_info`（发起方/对方）与 `group_info`（群名），仿 `ReportReadMapper` 范式
  - `CallLogQuery` 增 `startTime` / `endTime`（过滤 `create_time` 范围，纯代码无 DB 变更）；新增 `AdminCallLogVO`
- 前端: 新增 `views/admin/CallLogList.vue`（仿 `ReportList.vue` 筛选列表）；`Admin.vue` 菜单、`router` 子路由 `callLog`、`Api.js` 端点
- 数据库: **无表结构变更**（L3 判定依据；`call_log` 表 migration-008 已建好）

## Capabilities

- C1: 管理员可分页筛选通话记录（类型 / 媒体 / 状态 / 发起人 / 时间范围），列表返回双方昵称与群名；服务端强制排序，注入尝试无效
- C2: 非管理员调用该端点被拦截返回 `CODE_404`

## Impact

- 对外接口: 新增 1 个只读端点 `/admin/callLog/loadCallLog`；`check-api-contract` 需同步前端 `Api.js`；无新错误码，既有码语义不变
- 存量数据: 无影响，不迁移
- 性能 / 安全: `call_log` 仅有 `idx_caller/idx_group/idx_peer`，无筛选条件时分页走全表——当前通话记录量级小可接受，量大后再评估索引；`orderBy` 注入面由服务端硬编码覆盖封死
- 回退方案: 删除本变更代码即可，无数据回滚

---

## ☑ 人工确认关卡

> 本提案经 人工（会话内当场指令） 于 2026-09-29 确认，允许进入实施阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
