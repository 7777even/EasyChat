# Tasks — 通话记录管理端列表查询

- 关联 Design: 2026-09-29-call-log-admin/design.md
- 创建日期: 2026-09-29
- 预估总工时: 7h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。本变更无既有后端测试目录，后端 TDD 以「先写失败断言的活体冒烟用例（curl，不入库）」代替单测；前端以先写失败的接口契约核对为准（仿 `2026-09-26-sensitive-word-admin` 先例）。

## 前置（L3 关卡，已完成）

- [x] **L3 proposal 关卡**（2026-09-29 人工确认「同意方案，允许实施」）：端点/JOIN read mapper/前端页面 — ≤10min
- [x] 四件套（proposal/design/tasks/spec-delta/.openspec.yaml）完成并过人工确认关卡 — ≤1.5h

## 阶段一：查询层

- [x] **[TDD]** 先写失败冒烟断言：非管理员调用应 404、管理员调用应返回分页结构 + 昵称/群名字段、`orderBy=id desc;drop` 传入应被服务端覆盖不生效 — ≤30min
- [x] `CallLogQuery` 增 `startTime`/`endTime` 字段（中文注释）；新增 `AdminCallLogVO`（逐字段中文注释） — ≤30min
- [x] **[TDD]** `CallLogReadMapper` 接口 + XML：`count` + 分页 `list`，LEFT JOIN `user_info`（发起方/对方）+ `group_info`，`callType/mediaType/status/callerId/peerId/groupId/startTime/endTime` 条件全部 `#{}`，`durationMs = end_time - start_time` 计算列 — ≤1.5h

## 阶段二：服务与接口

- [x] **[TDD]** `AdminCallLogService(+Impl)`：首行硬编码 `query.setOrderBy("cl.id desc")` 覆盖（安全注释），`SimplePage` + `PageSize.SIZE15` 默认，出参 `PaginationResultVO` — ≤1h
- [x] `AdminCallLogController`：`POST /admin/callLog/loadCallLog` + `@GlobalInterceptor(checkAdmin = true)` — ≤30min
- [x] 后端 `mvn compile` 通过 — ≤30min

## 阶段三：前端

- [x] **[TDD]** `views/admin/CallLogList.vue`（仿 `ReportList.vue`：类型/媒体/状态下拉 + 日期范围 + 表格 + 分页；昵称/群名/时长 NULL 显示「—」） — ≤2h
- [x] `Admin.vue` 菜单项 + `router` 子路由 `callLog` + `Api.js` 端点 `loadCallLog`；`request.js` 包络对齐 `Result<T>` — ≤1h

## 阶段四：验证

- [x] 后端启动 + 活体冒烟：管理员分页（默认 15 条）/四类筛选各自生效/时间范围含边界/JOIN 返回昵称群名/`orderBy` 注入尝试被覆盖/非管理员 404 — ≤1h
  - 结果：44/44 PASS（`engineering/qa/2026-09-29-call-log-smoke.txt`）。注：非管理员现行契约为 HTTP 400 + body 1003（`bdf854f` 错误码重构后），非 HTTP 404。
- [x] `npm run build` + `check-api-contract`（1 新路由 0 漂移）+ `check-openspec-hygiene` — ≤30min

## 阶段五：收尾

- [x] QA/Retro 落 `engineering/`（L3 必写，附 curl 冒烟存证） — ≤30min
  - `engineering/qa/2026-09-29-call-log-admin.md` + `2026-09-29-call-log-smoke.txt`（44/44）；`engineering/retro/2026-09-29-call-log-admin.md`
- [x] spec-delta 回写 `openspec/specs/voice-call/spec.md` + `git mv` 归档 Change + 按域分笔提交（`type(scope): 单行`） — ≤30min
  - spec 已回写（新增 call-log-admin-list / call-log-admin-guard 两 Requirement + C5 口径修改）；Change 已移至 `openspec/archive/2026-09-29-call-log-admin`（目录原为未跟踪，用文件系统 mv 后整体入库，等效 git mv）；提交按 backend / frontend / openspec / QA 拆分。

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-29-call-log-admin/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵「对外接口增删改」行执行：`mvn compile` 0 error + 接口链路冒烟通过（44/44）
- [x] 契约已同步：`Api.js` 端点与 Controller 路由一致（`check-api-contract` 通过）
- [x] 归档闭环完成（spec-delta 回写 `specs/voice-call/` + Change 移入 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
