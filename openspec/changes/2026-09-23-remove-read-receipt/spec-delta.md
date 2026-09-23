# Spec Delta — 删除已读回执功能

- 关联 Proposal: 2026-09-23-remove-read-receipt/proposal.md
- 影响 capability: `multi-device-sync`（既有）
- 创建日期: 2026-09-23

## 新增

（无——本变更为特性下线，不引入新能力 Requirement。）

## 修改

### Requirement: 跨端会话状态同步（`openspec/specs/multi-device-sync/spec.md` L43，迁移 Scenario）

会话元数据变更（最后一条消息、未读数、置顶等）应在多端实时同步。（Requirement 正文不变。）

#### Scenario: 未读数多端一致（自「跨端已读状态广播」迁入，归属 -6 帧体系，与已读回执无关）

- **WHEN** 用户 U 在设备 A 查看会话 S 后清除未读
- **THEN** SYNC_SESSION (-6) 帧通知 U 的其他设备更新会话 S 的 `noReadCount = 0`

### Requirement: 离线消息多端共享（`spec.md` L114 去重机制表述修正，随回填一并执行）

- 原文「已通过 `message_read_record` 去重」修正为「按会话 `seq > lastSeq` 增量补推去重」。
- 理由：该表从未参与去重实现（全仓引用仅回执服务，grep 佐证），表 DROP 后原表述成为无载体的错误事实；实际机制为 INIT/SYNC 的 seq 增量（冒烟日志 `SYNC 补推 ... seq > ?` 佐证）。

## 移除

### Requirement: 跨端已读状态广播（`openspec/specs/multi-device-sync/spec.md` L26-34）

移除理由：已读回执功能整体下线（用户指令）；`-3` 上报帧、`-5` 通知帧、`markRead`、`message_read_record` 均不存在，规格承诺失去实现载体。

#### Scenario: A 端已读，B 端实时同步（随 Requirement 移除）

- ~~**WHEN** 用户 U 在设备 A 打开某会话，触发 `markRead`~~
- ~~**THEN** 发送方通过 ACK_NOTIFY (-5) 收到已读通知~~
- ~~**AND** U 的设备 B 也收到 ACK_NOTIFY (-5)~~

> 注：原 Requirement 正文「更新会话未读计数和消息的已读/送达标记」中，未读计数部分由「跨端会话状态同步」承接（-6 帧），已读/送达标记部分随本移除作废。

## UI 原型描述（对齐前端 AGENTS §4，截图证据见 QA）

- **删除前 · 聊天气泡（ChatMessage.vue）**：自己发送的单聊文本/图片（contactType=0、messageType 2/5、status=1）气泡右侧下方挂一行 `.read-status-tip` 小字徽标——ackType=3 显示「已读」、ackType=2 显示「已送达」、无 ackType 时渲染空白占位（11px 灰字、右浮动）。
- **删除后**：气泡下不再渲染任何徽标节点，`.read-status-tip` 样式块删除。保持不变：气泡本体（头像 / 内容 / 发送中骨架屏 / 撤回样式）、会话列表未读红点（noReadCount）、发送状态（发送中 → 已发送）。

## 追溯矩阵（Requirement ↔ Task）

| spec-delta 条目 | 对应 Task（tasks.md） | 验收锚点 |
|---|---|---|
| 移除 Requirement「跨端已读状态广播」 | T1、T2、T3、T4 | 双仓 grep：`messageReadService|sendAckNotify|ackNotify|ackType|markRead|batchGetAck|message_read` 全 0 |
| 修改：Scenario「未读数多端一致」迁移 | T5 | specs 中该 Scenario 存在于「跨端会话状态同步」下；`已读|markRead|ACK_NOTIFY` 在 specs grep 为 0 |
| （Capability C1 · 双端无 -3/-5 行为） | T6 | 冒烟证据：无 -3/-5 踪迹，收发/未读/撤回回归通过 |

## 回填记录

- [x] T5 执行时合入 `openspec/specs/multi-device-sync/spec.md`（2026-09-23 已合入：移除「跨端已读状态广播」Requirement、迁入未读数 Scenario、修正离线去重表述；归档前置，§7.1）
