# QA：举报处理与管理端审计（report-admin）

- **范围**：`2026-09-26-report-admin` Change —— 管理端举报查看/详情/处置 + 审计日志，前后端全量落地。
- **能力归属**：`content-moderation`（扩展「举报处理 + 审计」子能力）。
- **分级**：L3（新业务能力 + 数据模型 + 管理端权限语义）。用户已明确「做」视为人工确认关卡通过。

## 验收口径（来自 tasks.md）
1. 管理员可分页查看全部举报，按类型/状态/理由/时间过滤
2. 管理员可查看单条举报详情（含被举报内容全文与双方信息）
3. 管理员可处置：置已处理/已驳回，记录处理人/时间/备注，可选删内容/封禁发布者
4. 每次处置动作写入一条不可变审计日志，可按要求查询
5. 非管理员调用管理端接口被 `checkAdmin` 拦截（CODE_404）
6. 编译/构建/三道门禁全绿；按域拆分提交并推送成功

## 实际执行命令与用例数
| 验证项 | 命令 / 动作 | 结果 |
|---|---|---|
| 后端编译 | `mvn -o -B clean compile` | BUILD SUCCESS（180 源，含新增） |
| 前端构建 | `electron-vite build --outDir out-verify` | ✓ built in 44.64s（ReportList chunk 24.38kB） |
| IPC 注册门禁 | `node scripts/check-ipc-registration.mjs --strict` | 33/33 ✓（本次未新增 IPC 通道，符合预期） |
| 接口契约门禁 | `node scripts/check-api-contract.mjs` | 92 路由 / 90 调用 / **0 漂移** / 2 历史孤儿（download 类，非本次） |
| openspec 卫生 | `node scripts/check-openspec-hygiene.mjs` | 1 错（change 已全勾未归档）—— 本 QA 后归档即归零 |

证据见同目录 `2026-09-26-report-admin-evidence.txt`。

## 代码路径核对（逐场景）
- 列表 UNION 读视图：`ReportReadMapper.xml#report_union`（moment_report + message_report 投影统一列，LEFT JOIN 取内容摘要与举报人昵称）
- 详情：`AdminReportServiceImpl.getReportDetail` 按 reportType 取 moment/comment/message 全文 + 发布者信息
- 处置：`dealReport` 校验 status∈{1,2}、handleAction∈{0,1,2}；action=1 对 moment/comment 置 status=0 软删，message 仅记录（无删除状态位）；action=2 调 `userInfoService.updateUserStatus(0, publisherId)` 封禁；末了写 `report_audit_log`
- 审计：`loadAuditLog` 按 reportId/type/adminId/action/时间过滤
- 权限：`AdminReportController` 四接口均 `@GlobalInterceptor(checkAdmin = true)`
- 前端：`views/admin/ReportList.vue`（列表+详情弹窗含审计时间线+处置弹窗）；`Api.js` 4 端点；`router` 子路由 `reportList`；`Admin.vue` 菜单「举报管理」

## 未运行项（如实标注）
- **活体 HTTP 冒烟未执行**：后端未启动（无 token），且管理端需 admin=true 账号。以构建 + 三道门禁 + 逐场景代码路径审查替代（本表「代码路径核对」）。
- **DB 迁移未执行**：`easychat-migration-006-report-admin.sql` 为手动执行（与 migration-005 同策略）。未执行时，UNION/审计表在运行期不可用；已在 easychat.sql 基线同步，新库可直接建表。
- **前端 UI 未做 Electron 内运行验证**：构建通过 + Vue SFC 编译产出确认；交互逻辑以代码审查确认。

## 结论
后端编译、前端构建、三道门禁（除「未归档」一项，归档后即零）均通过；代码路径逐场景核对符合设计。功能具备交付条件。建议上线前：① 在测试库执行 migration-006；② 用 admin 账号跑一遍「列表→详情→处置→审计时间线」手动清单。

## 手动验证清单（上线前）
1. MySQL 执行 `easychat-migration-006-report-admin.sql`
2. 管理员登录 → 管理端 → 举报管理：看到待处理举报列表
3. 点「查看」：看到被举报内容全文 + 举报人/发布者 + 审计时间线（空）
4. 点「处理」：选「已处理」+「删除被举报内容」→ 确认；被举报动态/评论在前端不可见
5. 再次「查看」：审计时间线出现一条「已处理 / 删除被举报内容」记录
6. 选「封禁发布者」处置一条 → 该发布者被禁用（登录被拒「账号已禁用」）
7. 非管理员 token 调 `/admin/report/loadReport` → CODE_404
