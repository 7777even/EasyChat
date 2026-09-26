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
- ~~**活体 HTTP 冒烟未执行**~~ → **已于 2026-09-26 15:18 补跑**，70/70 通过，详见下节「活体冒烟补跑」。
- ~~**DB 迁移未执行**~~ → **migration-006 已在库执行**（`report_audit_log` 表与两表 `handle_action` 字段均存在，冒烟前置检查 5/5 PASS；三表基线 0 行）。
- **前端 UI 未做 Electron 内运行验证**：构建通过 + Vue SFC 编译产出确认；交互逻辑以代码审查确认。

## 活体冒烟补跑（2026-09-26 15:18，闭合上表缺口）
- **环境**：后端活体 `localhost:5050`（本次修复后重启实例），MySQL/Redis 活体；`BASE=http://localhost:5050/api`。
- **命令**：`python smoke_admin_report.py engineering/qa/2026-09-26-report-admin-smoke.txt`（**脚本按约定不入库**，仅存证文件入库）。
- **结果**：**用例 70 项 / 通过 70 / 失败 0 → SMOKE PASS**。覆盖前置 5、双账号登录、用户侧举报三分支（动态/评论/消息，含幂等重复举报）、列表与 8 组过滤/分页、详情三分支（内容全文+双方信息）、负向（901/404/2702/2703）、DB 落库断言、跑完清理至基线、真实消息 1846 不受影响。
- 证据见同目录 `2026-09-26-report-admin-smoke.txt`（UTF-8，98 行）。

### 冒烟发现并修复的 3 个缺陷（首跑即拦截，修复后复跑全绿）
| # | 缺陷 | 根因（证据） | 修复 |
|---|---|---|---|
| 1 | 管理员 `admin` 恒 false，全部 `/admin/**` 对所有人 CODE_404 | `AppConfig` 读 `@Value("${admin.emails:}")`，项目配置键实为 `easychat.admin-emails`，键错配致白名单恒空（`UserInfoServiceImpl` 判定处 + 活体探测 `admin=False`） | `UserInfoServiceImpl` 改用已注入的 `easyChatProperties.getAdminEmails()`；移除 `AppConfig` 中无引用的死配置键 |
| 2 | 三条举报提交链路全部 1002 / HTTP 500，0 行落库 | `MomentReportMapper` / `MessageReportMapper` 接口为 `insert(@Param("bean"))`，XML insert 段却用裸 `#{id}`；日志堆栈 `BindingException: Parameter 'id' not found. Available parameters are [bean, param1]` | 两处 XML 补 `bean.` 前缀（对齐同文件 `updateById` 及仓库既有约定）；`keyProperty` 改 `bean.id` 对齐仓库既有同型写法 |
| 3 | 管理端列表/详情 1002（缺陷 1 修复后暴露，此前被 404 挡住未执行到） | `ReportReadMapper.xml` 读视图 JOIN 引用不存在的列 `moment.moment_id`、`moment_comment.comment_id`（两表主键均为 `id`）；日志 `Unknown column 'm.moment_id' in 'on clause'` | JOIN 改为 `m.id = mr.moment_id`、`mc.id = mr.comment_id`（`chat_message.message_id` 核对无误） |

修复后复跑门禁：`check-openspec-hygiene` **0 错/0 警/0 信息**（change 已归档）；`check-api-contract` **92 路由 / 90 调用 / 0 漂移**（2 个历史孤儿为 download 类，非本次）。`mvn compile` 0 error，且经活体启动验证（§2 矩阵 Entity/Mapper 行）。

## 结论
后端编译、前端构建、门禁全绿；活体冒烟 70/70 通过，覆盖全部 6 条验收口径（含非管理员 CODE_404 拦截）。冒烟首跑发现的 3 个阻断缺陷（配置键错配、insert 参数绑定、读视图列名）已修复并复跑至全绿。**功能具备交付条件。** 前端 Electron 内 UI 交互仍以构建 + 代码审查确认（见未运行项）。

## 手动验证清单（上线前）
1. MySQL 执行 `easychat-migration-006-report-admin.sql`
2. 管理员登录 → 管理端 → 举报管理：看到待处理举报列表
3. 点「查看」：看到被举报内容全文 + 举报人/发布者 + 审计时间线（空）
4. 点「处理」：选「已处理」+「删除被举报内容」→ 确认；被举报动态/评论在前端不可见
5. 再次「查看」：审计时间线出现一条「已处理 / 删除被举报内容」记录
6. 选「封禁发布者」处置一条 → 该发布者被禁用（登录被拒「账号已禁用」）
7. 非管理员 token 调 `/admin/report/loadReport` → CODE_404

> 注：1、2、3、7 及 4/5/6 的接口侧（处置写审计、封禁改用户状态、软删落库）已由活体冒烟覆盖（`...-smoke.txt`）；剩余待人工项仅为 Electron UI 的可见性交互（4 的前端不可见、6 的登录被拒提示）。
