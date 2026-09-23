# Spec Delta — 单聊撤回实时同步到发送方自己的多台设备

- 关联 Tasks: 2026-09-23-recall-sender-sync/tasks.md
- 创建日期: 2026-09-23

> 格式对齐 `openspec/specs/multi-device-sync/spec.md`。
> 与 proposal Capabilities 一一对应。
> 目标 spec 中 C4 主 Scenario 已存在（承诺即正确），本 delta 对同一 Requirement 补充实现性约束与边界场景。

## ADDED Requirements

（无）

## MODIFIED Requirements

### Requirement: 跨端消息撤回同步

任一设备撤回的消息，其他在线设备也应看到撤回提示；本变更补全发送方侧投递，并约束发送方侧帧的联系人语义保持为会话对方。

#### Scenario: A 端撤回消息，B 端实时看到（原 Scenario 保留）

- **WHEN** 用户 U 在设备 A 撤回一条消息
- **THEN** 服务端广播撤回帧（messageType=14）给 U 的所有设备
- **AND** 设备 B 同步更新本地消息为撤回状态

#### Scenario: A 端撤回消息，A 的另一台设备实时看到（本变更补全）

- **WHEN** 用户 U 同时在线设备 A、B，在设备 A 撤回一条与用户 V 的单聊消息
- **THEN** 服务端将未做联系人转换的撤回帧副本投递给 U 的设备 B（`USER_CONTEXT_MAP.get(sendUserId)`）
- **AND** 设备 B 把该消息更新为已撤回（messageId 幂等）
- **AND** 副本帧内 `contactId` 保持为会话对方 V（≠ sendUserId），设备 B 不产生以自己为联系人的脏会话

#### Scenario: 群聊撤回（既有行为，分支确认）

- **WHEN** 群成员在任一设备撤回群消息
- **THEN** 撤回帧经群 ChannelGroup 广播到所有群成员设备（含撤回者自己的其他设备）
- **AND** 发送方副本分支仅存在于单聊（USER）分发路径，群聊不产生重复帧

#### Scenario: 发送方无其他在线设备

- **WHEN** 发送方除撤回发起设备外无任何在线设备
- **THEN** 副本投递判空跳过，不入离线缓冲
- **AND** 发送方设备后续拉取历史消息时从 DB 读到已改写的"该消息已撤回"内容（既有持久化兜底）

**变更前（引用原 spec）**: 「THEN 服务端广播撤回帧（messageType=14）给 U 的所有设备」——仅有此总则承诺，未约定发送方侧 `contactId` 语义、群聊分支关系与离线兜底边界。

**变更后**: 总则承诺不变，补充发送方侧副本投递的帧语义约束（contactId=会话对方）、单聊/群聊分支互斥与无在线设备的兜底行为。

---

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | MODIFIED: 跨端消息撤回同步 | 1.1, 1.2, 1.3, 2.1 |
| （前端零改动核验） | MODIFIED: 同上（Scenario 幂等更新） | 1.5 |
| （权限现状核验） | MODIFIED: 同上（复用既有校验） | 1.4 |

## 回填记录

- 已于 2026-09-23 回写 `openspec/specs/multi-device-sync/spec.md`：Requirement 描述更新 + 新增 3 个 Scenario（发送方侧帧语义防错 / 群聊分支确认 / 发送方无其他在线设备），原 Scenario 标题去掉「（本变更补全）」等过程性后缀。
