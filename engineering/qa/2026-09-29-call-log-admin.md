# QA — 通话记录管理端列表查询（call-log-admin）

- 日期: 2026-09-29
- 效率等级: L3
- 范围: 后端 `AdminCallLogController` / `AdminCallLogService(+Impl)` / `CallLogReadMapper(+XML)` / `AdminCallLogVO` / `CallLogQuery`；前端 `views/admin/CallLogList.vue`、`Admin.vue` 菜单、`router/index.js` 子路由、`utils/Api.js`。端点 `POST /api/admin/callLog/loadCallLog`。数据表 `call_log`（migration-008，本机首次执行）。

## 验收口径

- 新端点返回 `Result<T>` 包络，`code=0` 才取 `data`；分页出参 `PaginationResultVO`（`list` + `totalCount`）。
- 默认分页 `PageSize.SIZE15`：不传 `pageSize` 时首页 15 条（tasks 阶段四）。
- 四类筛选（`callType` / `mediaType` / `status` / `callerId|peerId|groupId`）各自生效且可组合；时间范围 `startTime`/`endTime` 对 `create_time` 按 `>= / <=` 含边界过滤。
- LEFT JOIN 解析发起方/对端昵称与群名；单聊行无群名、群发行无对端昵称（字段隔离）；`durationMs = end_time - start_time`，`end_time` 为 NULL 时计算列为 null。
- 安全：`orderBy` 由服务端硬编码 `cl.id desc` 覆盖，注入尝试（`id desc; DROP TABLE` / `sleep(3)`）不生效且表不被污染；SQL 条件全 `#{}`。
- 权限（现行契约，`bdf854f` 错误码重构后）：无 token → HTTP 401 + body `2001` 登录超时；非管理员（`checkAdmin`）→ HTTP 400 + body `1003`。
- 契约同步：`Api.js` 调用路径与 Controller 路由一致，`check-api-contract` 0 漂移（AGENTS §7.4 文档同步）。

## 实际执行命令与结果

- `mvn compile` → 0 error。
- `node scripts/check-api-contract.mjs` → 98 后端路由 / 96 前端调用 / 13 主进程 / 0 孤儿 / 0 漂移。
- `npm run build` → 0 error，`CallLogList` chunk 正常产出。
- 后端启动（`mvn spring-boot:run`，MySQL57 + Redis 在线）+ `python scripts/smoke/smoke_call_log.py` → **用例 44 项 / 通过 44 / 失败 0 → SMOKE PASS**。覆盖：前置与 fixture 落库 18 行、双账号登录与 admin 身份、分页/排序/默认 15 条、计算列与 NULL 时长、JOIN 昵称群名（含注销用户）、9 组筛选与时间边界 4 例、注入防御 4 例、权限 2 例、清理与真实数据基线不变。
- 过程修复（冒烟抓出）：`CallLogQuery.startTime/endTime` 原缺 getter/setter，OGNL 求值失败致接口 500——补齐后重跑通过。

## 未运行项

- **Electron 实机页面截图走查未执行**（沙箱无 GUI）：CallLogList 菜单入口/筛选交互/「—」占位渲染由用户本机手动清单验证，随 ①②④ 手动项一并输出。
- 真实通话记录写入链路（`CallService.save`）不在本变更（纯只读查询），由 voice-call 既有 QA 覆盖；本次冒烟真实记录基线 0 行全程不变。
- Java 单测目录 `src/test` 不存在，[TDD] 按先例以活体冒烟断言替代（tasks.md 头部已声明）。

## 证据附件

- `2026-09-29-call-log-smoke.txt`（同目录）：44/44 PASS 全量终端输出，含权限、注入、边界、清理断言。

## 结论

- **达成**：验收口径逐条满足，冒烟全绿，契约 0 漂移。遗留仅 UI 截图走查（转用户本机手动清单，非代码缺陷）。
