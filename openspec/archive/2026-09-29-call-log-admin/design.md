# Design — 通话记录管理端列表查询

- 关联 Proposal: 2026-09-29-call-log-admin/proposal.md
- 创建日期: 2026-09-29

## 1. 架构设计

沿用管理端既定模式「一域一 Controller 一 Service 一页」（与 `AdminReportController`/`ReportList.vue` 同构）。

```
AdminCallLogController ──> AdminCallLogService ──> CallLogReadMapper (XML)
                          │  (orderBy 硬编码覆盖      │  count + 分页 list
                          │   SimplePage + SIZE15)    │  LEFT JOIN user_info ×2
                          │                           └ LEFT JOIN group_info
                          └ 出参 PaginationResultVO<AdminCallLogVO>
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | 新增 `AdminCallLogController` 1 端点 | 路由 + 参数绑定 + 调 Service，不写业务不碰 Mapper |
| Service | 新增 `AdminCallLogService(+Impl)`；`CallLogQuery` 增 `startTime/endTime` | 分页编排 + **orderBy 服务端硬编码覆盖** + 时间范围归一 |
| Mapper / SQL | 新增 `CallLogReadMapper` 接口 + XML（count、分页列表、LEFT JOIN、筛选条件） | 只写 SQL，全部 `#{}` 绑定 |
| Entity | 新增 `AdminCallLogVO`；`CallLogQuery` 增 2 字段 | 出参对象逐字段中文注释 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 主进程 / preload | 无改动 | — |
| 渲染进程 | 新增 `views/admin/CallLogList.vue`；`Admin.vue` 菜单、`router` 子路由 `callLog`、`Api.js` 1 端点 | 只经 `request.js` 访问接口 |

## 2. 接口设计

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/admin/callLog/loadCallLog` | POST | `CallLogQuery`：`pageNo`、`pageSize`（默认 15）、`callType`(1单聊/2群呼)、`mediaType`(1音频/2音视频)、`status`(1已接/2未接/3拒接/4取消/5忙线)、`callerId`、`peerId`、`groupId`、`startTime`、`endTime`（`create_time` 毫秒范围，含边界） | `Result<PaginationResultVO<AdminCallLogVO>>` | `@GlobalInterceptor(checkAdmin = true)` |

### AdminCallLogVO 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 记录 ID |
| callType | Integer | 1=单聊 2=群呼 |
| mediaType | Integer | 1=音频 2=音视频 |
| status | Integer | 1已接 2未接 3拒接 4取消 5忙线 |
| callerId / callerNickName | String | 发起方 userId / 昵称（LEFT JOIN，注销后可能为 null，前端显示「—」） |
| peerId / peerNickName | String | 单聊对方 userId / 昵称 |
| groupId / groupNickName | String | 群呼群组 ID / 群名 |
| startTime / endTime | Long | 通话开始/结束时间（ms） |
| durationMs | Long | 时长 = `end_time - start_time`（SQL 计算，null 时前端显示「—」） |
| participantCount | Integer | 参与人数 |
| createTime | Long | 记录创建时间（ms） |

### 错误码

无新增。非管理员由拦截器统一返回 `CODE_404`；参数类型非法由 Spring 绑定返回 400。

## 3. 数据模型

无结构变更。查询条件（`startTime/endTime` 等）均为既有列的过滤。

## 4. 安全设计

- 鉴权: 全端点 `checkAdmin = true`，非管理员 `CODE_404`
- 数据权限: 通话记录属全局运营数据，仅管理员可读；无用户侧端点
- 输入校验: 枚举型筛选（callType/mediaType/status）由前端下拉约束，服务端不信任、仅作等值过滤；时间范围为 Long 毫秒
- SQL 注入防护: **所有条件与 JOIN 键用 `#{}`**；唯一 `${}` 面是 `CallLogMapper` 既有 `order by ${query.orderBy}` —— 本变更列表查询走新 `CallLogReadMapper`，且 **Service 首行 `query.setOrderBy("cl.id desc")` 硬编码覆盖**，HTTP 传入的 `orderBy` 一律丢弃（记录安全注释）

## 5. ADR

### ADR-001: 昵称/群名解析用 LEFT JOIN read mapper，而非 Service 内逐行查询

- 状态: 已接受
- 上下文: 列表需展示发起方/对方昵称与群名。`UserInfoMapper` 仅有单查 `selectByUserId`，逐行查为 N+1（页 15 行最多 31 次查询）
- 决策: 新建 `CallLogReadMapper`，单条 SQL LEFT JOIN `user_info` ×2 + `group_info`，仿已归档的 `ReportReadMapper` 先例
- 后果: 正面——一次查询、与管理端既有范式一致；负面——新增 1 个 Mapper 接口 + XML；注销用户昵称可能为 NULL，VO 保持 null、前端显示「—」

### ADR-002: 分页范式沿用 `BaseParam` + `PaginationResultVO`，非 §4 `PageRequest`/`PageResult`

- 状态: 已接受
- 上下文: 根 AGENTS §4 规定分页统一 `PageRequest`/`PageResult`，但管理端 6 个既有页面（loadReport/loadWord 等）事实标准为 `BaseParam.pageNo/pageSize` + `PaginationResultVO`
- 决策: 沿用管理端既有范式，与 `loadReport` 逐行对齐（`SimplePage` + `PageSize.SIZE15` 默认）
- 后果: 正面——管理端口径一致，前端表格组件复用；负面——与 §4 字面不一致，voice-call spec C5 中 `PageRequest/PageResult` 表述系服务层查询口径，管理端列表作为新增独立 Requirement 规避冲突（见 spec-delta MODIFIED 说明）

### ADR-003: 时间范围过滤选 `create_time` 而非 `start_time`

- 状态: 已接受
- 上下文: 管理员按时间找通话；`start_time` 来源于房间状态、DDL 为 `DEFAULT NULL`，`create_time` 由 `CallService` 落库时必填
- 决策: `startTime/endTime` 过滤 `create_time`（记录创建 ≈ 通话结束，毫秒范围含边界）
- 后果: 正面——恒有值、无 NULL 行漏出；负面——与「通话开始时间」语义略有偏差，VO 同时返回 `startTime` 供展示

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 无筛选全表分页随记录增长变慢 | 低 | 低 | 当前量级小；量大后为 `create_time` 补索引（独立变更） |
| `orderBy` 注入 | 高（若不防） | 高 | Service 首行硬编码覆盖 + read mapper 不引用 `query.orderBy`，冒烟含注入尝试断言 |
| 已注销用户昵称 NULL | 中 | 低 | LEFT JOIN + 前端「—」兜底 |
| 群已解散后群名 NULL | 中 | 低 | 同上（`group_info` 物理删除场景） |

## 7. 依赖与前提

- migration-008 `call_log` 表已存在（本仓库已交付）
- 无其他进行中 Change 依赖；管理端菜单/路由骨架（Admin.vue/router/Api.js）已有 6 页先例
