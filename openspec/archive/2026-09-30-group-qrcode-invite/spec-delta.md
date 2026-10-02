# Spec Delta — 群二维码与群邀请

- 关联 Tasks: 2026-09-30-group-qrcode-invite/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/group-qrcode-invite/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 群二维码（C1 + C2）

群主/管理员生成群二维码，用户扫描二维码加入群组。

#### Scenario: 生成群二维码

- **WHEN** 群主/管理员点击"生成群二维码"
- **THEN** 服务端生成群二维码 token 并返回
- **AND** 前端显示群二维码图片

#### Scenario: 扫描二维码加入群组

- **WHEN** 用户扫描二维码
- **THEN** 前端调用加群接口
- **AND** 用户加入群组

---

### Requirement: 群邀请链接（C3 + C4）

群主/管理员生成群邀请链接，用户通过邀请链接加入群组。

#### Scenario: 生成群邀请链接

- **WHEN** 群主/管理员点击"生成邀请链接"
- **THEN** 服务端生成邀请链接 token 并返回
- **AND** 前端显示邀请链接

#### Scenario: 通过邀请链接加入群组

- **WHEN** 用户点击邀请链接
- **THEN** 前端调用加群接口
- **AND** 用户加入群组

---

## MODIFIED Requirements

无修改已有规格。

---

## REMOVED Requirements

无移除已有规格。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 群二维码 | 1.2, 1.4, 3.1 |
| C2 | ADDED: 群二维码 | 1.2, 1.4, 3.1 |
| C3 | ADDED: 群邀请链接 | 1.3, 1.4, 3.2 |
| C4 | ADDED: 群邀请链接 | 1.3, 1.4, 3.2 |
