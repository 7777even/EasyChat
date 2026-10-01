# Spec Delta — 浮窗

- 关联 Tasks: 2026-09-30-floating-window/tasks.md
- 创建日期: 2026-09-30

## ADDED Requirements

### Requirement: 进入浮窗模式

用户可通过会话右键菜单将聊天窗口最小化为悬浮小窗。

#### Scenario: 右键菜单进入浮窗

- **WHEN** 用户右键点击会话并选择「浮窗」
- **THEN** 创建浮窗窗口（300x400，置顶）
- **AND** 浮窗显示当前聊天内容

---

### Requirement: 浮窗窗口特性

浮窗窗口置顶显示，固定大小 300x400。

#### Scenario: 浮窗窗口显示

- **WHEN** 浮窗窗口创建
- **THEN** 窗口置顶显示，大小 300x400
- **AND** 窗口无边框，可拖动

---

### Requirement: 退出浮窗模式

点击浮窗可关闭并回到正常聊天窗口。

#### Scenario: 点击浮窗关闭

- **WHEN** 用户点击浮窗窗口
- **THEN** 关闭浮窗窗口
- **AND** 回到正常聊天窗口

---

## MODIFIED Requirements

无

---

## REMOVED Requirements

无

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 进入浮窗模式 | 1.1, 1.2 |
| C2 | ADDED: 浮窗窗口特性 | 1.2 |
| C3 | ADDED: 退出浮窗模式 | 1.2 |
