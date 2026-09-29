# Spec Delta — 通话记录管理端列表查询

- 关联 Tasks: 2026-09-29-call-log-admin/tasks.md
- 创建日期: 2026-09-29

> 格式对齐 `openspec/specs/voice-call/spec.md`。
> 与 proposal Capabilities C1–C2 一一对应。
> 本 delta 扩展既有 `voice-call` capability（通话记录子能力），交付 C5 中声明为「后续独立变更」的管理端列表查询。

## ADDED Requirements

### Requirement: 通话记录管理端列表查询（call-log-admin-list）— C1

管理员（token `admin=true`）必须能够分页筛选通话记录，列表返回双方昵称与群名，且排序由服务端强制、不受请求参数影响。

#### Scenario: 管理员分页筛选通话记录

- **WHEN** 管理员调用 `POST /admin/callLog/loadCallLog`（可选 `callType/mediaType/status/callerId/peerId/groupId/startTime/endTime` + `pageNo/pageSize`，pageSize 缺省 15）
- **THEN** 返回 `Result<PaginationResultVO<AdminCallLogVO>>`，按 `cl.id desc` 排序，分页语义沿用管理端 `BaseParam`/`PaginationResultVO` 惯例
- **AND** `startTime/endTime` 按 `create_time` 毫秒范围过滤（含边界）

#### Scenario: 列表解析昵称与群名

- **WHEN** 返回单聊记录（`callType=1`）与群呼记录（`callType=2`）
- **THEN** 单聊行返回 `callerNickName`/`peerNickName`（LEFT JOIN `user_info`），群呼行返回 `groupNickName`（LEFT JOIN `group_info`）
- **AND** 用户已注销或群已解散时对应昵称字段为 null，前端显示「—」

#### Scenario: orderBy 注入被服务端覆盖

- **WHEN** 请求携带恶意 `orderBy=id desc;drop table`（`BaseParam.orderBy` 可被 HTTP 绑定）
- **THEN** Service 首行硬编码 `setOrderBy("cl.id desc")` 覆盖请求值，SQL 不受污染
- **AND** read mapper 全部条件使用 `#{}` 参数绑定

---

### Requirement: 通话记录管理端权限隔离（call-log-admin-guard）— C2

`/admin/callLog/*` 接口 SHALL 使用 `@GlobalInterceptor(checkAdmin = true)`；非管理员调用 SHALL 被拦截返回 `CODE_1003`（HTTP 400，经 `inferHttpStatus` 兜底映射）；无 token SHALL 返回 `CODE_2001`（HTTP 401）。

> 口径注记：原 delta 草案写 `CODE_404`（沿用 09-26 前旧码），`bdf854f` 错误码统一分段后旧 404/901 已迁为 1003/2001，关卡方案语义（非管理员拦截、无 token 登录超时）不变，此处按现行 `ResponseCodeEnum` 订正。

#### Scenario: 非管理员调用

- **WHEN** 普通用户调用 `POST /admin/callLog/loadCallLog`
- **THEN** 返回 HTTP 400 + `CODE_1003 资源不存在`，不泄露通话记录数据

#### Scenario: 无 token 调用

- **WHEN** 未携带 token 调用 `POST /admin/callLog/loadCallLog`
- **THEN** 返回 HTTP 401 + `CODE_2001 登录超时，请重新登录`

---

## MODIFIED Requirements

### Requirement: 通话记录持久化（voice-call-log）— 已有能力口径同步

#### Scenario: 查询可回溯（落库口径）

- **WHEN** 调用方按 `caller_id` / `peer_id` / `group_id` 查询
- **THEN** 返回对应 `call_log` 列表（分页遵循 `PageRequest`/`PageResult`）；管理端列表查询已由 `call-log-admin-list` 交付（`POST /admin/callLog/loadCallLog`），不再排除在规格范围外

**变更前（引用原 spec）**: 「…v1 仅落库，管理端列表查询为后续独立变更，不在本范围」

**变更后**: 「…管理端列表查询已由 `call-log-admin-list` 交付（`POST /admin/callLog/loadCallLog`，管理端分页沿用 `BaseParam`/`PaginationResultVO` 惯例）」

---

## REMOVED Requirements

无。

---

## 关键页面原型描述（UI 交互变更备案）

`views/admin/CallLogList.vue`（管理后台左侧新菜单项「通话记录」，路由 `/admin/callLog`）：

```
┌ 通话记录 ────────────────────────────────────────────────┐
│ [类型: 全部▾] [媒体: 全部▾] [状态: 全部▾] [日期范围____] [查询] │
├────┬──────┬──────┬──────────┬──────────┬───────┬──────┬───┤
│ ID │ 类型 │ 媒体 │ 发起人    │ 对方/群名 │ 状态  │ 时长 │… │
├────┼──────┼──────┼──────────┼──────────┼───────┼──────┼───┤
│ 12 │ 群呼 │ 音视频│ 张三      │ 产品群    │ 已接  │ 05:32│  │
│ 11 │ 单聊 │ 音频 │ 李四      │ 王五      │ 未接  │  —   │  │
└────┴──────┴──────┴──────────┴──────────┴───────┴──────┴───┘
  ← 1 2 3 … →（每页 15 条，居中分页器，仿 ReportList.vue）
```

- 类型/媒体/状态下拉选项来自 spec 枚举；日期范围绑定 `startTime/endTime`（毫秒）
- 昵称/群名/时长为 null 显示「—」；行含 `createTime`（记录时间）与 `participantCount`

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 通话记录管理端列表查询（call-log-admin-list） | 阶段一 2/3、阶段二 1/2、阶段三 1/2、阶段四 1 |
| C2 | ADDED: 通话记录管理端权限隔离（call-log-admin-guard） | 阶段二 2、阶段四 1 |
| 已有能力 voice-call-log | MODIFIED: 查询可回溯（落库口径） | 阶段五 2（回写） |
