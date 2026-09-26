# 提案：举报处理与管理端审计（report-admin）

## Why（为什么做）

前序 Change `2026-09-26-content-moderation` 已落地「用户侧举报入口 + 敏感词引擎」：
普通用户可对朋友圈动态 / 评论 / 聊天消息发起举报（写入 `moment_report` / `message_report`，
`status=0` 待处理），但**举报进来后无人处理、也无任何管理端能力**。运营/管理员无法查看、处置举报，
更无法追溯「谁在什么时间对哪条举报做了什么处理」——这既是合规要求，也是内容治理闭环的缺口。

本 Change 补齐「管理端」半环：管理员可 **查看举报、处置（已处理/已驳回）、对内容/发布者执行动作**，
并对每一次处置动作落 **审计日志**，实现「举报 → 处理 → 审计」完整闭环。

## What（做什么）

- 新增管理端举报接口（`/admin/report/*`，`checkAdmin=true`）：
  1. `loadReport`：分页列表（按类型/状态/理由/时间过滤，跨两张表统一视图）
  2. `getReportDetail`：举报详情（含被举报内容全文、举报人/发布者信息）
  3. `dealReport`：处置（置状态 1已处理/2已驳回，记录处理人/时间/备注，可选动作：删除内容 / 封禁发布者）
  4. `loadAuditLog`：审计日志列表（按举报/管理员/动作/时间过滤）
- 数据模型：
  - `moment_report` / `message_report` 补齐 `handle_user_id`、`handle_time`、`handle_note`、`handle_action`
    （当前 `message_report` 缺这三个字段、`moment_report` 缺 `handle_note`/`handle_action`，**先对齐字段**）
  - 新增 `report_audit_log` 表：每次处置动作追加一条不可变记录（举报闭环的审计源）
- 前端管理端新增「举报管理」页（`views/admin/ReportList.vue`）：列表 + 详情弹窗 + 处置弹窗 + 审计时间线。

## Capabilities（对应 spec 能力）

- `content-moderation`（扩展）：在既有「举报入口 + 敏感词」能力之上，新增「举报处理 + 审计」子能力。

## Impact（影响面）

- 后端：新增 1 Controller、1 Service、4 VO/Query、1 审计 PO、2 Mapper（UNION 读视图 + 审计日志）；
  修改 2 个举报 PO（补字段）；`ResponseCodeEnum` 新增 `CODE_2702`。
- 前端：新增 1 管理页，修改 `Api.js` / `router` / `Admin.vue` 菜单（新增「举报管理」）。
- 数据库：2 张举报表 ALTER 加字段 + 1 张新表（migration-006），并同步 `easychat.sql` 基线。
- 权限：复用既有 `checkAdmin` 拦截（`TokenUserInfoDto.admin`），无新增鉴权机制。
- 风险：处置动作会**实际修改内容/用户状态**（删内容、封禁发布者），属 L4 级数据影响，故实施前需人工确认——用户已明确「做」即视为确认。

## 人工确认关卡

- 处置动作会触发内容删除 / 用户封禁（不可逆影响）。**已与用户确认执行**（用户指令「推送完 做 举报的处理/管理端审计」）。
- 删除内容仅对朋友圈动态/评论生效（`status=0` 软删）；聊天消息无删除状态位，仅记录不物理删除。
- 封禁发布者复用既有 `userInfoService.updateUserStatus(0, userId)`（`UserStatusEnum.DISABLE`）。
