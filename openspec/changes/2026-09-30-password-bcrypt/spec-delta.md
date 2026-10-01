# Spec Delta — MD5 → BCrypt 密码加密升级

- 关联 Tasks: 2026-09-30-password-bcrypt/tasks.md
- 创建日期: 2026-09-30

> 格式对齐 `openspec/specs/password-bcrypt/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: BCrypt 密码加密（C1）

新注册用户使用 BCrypt 加密密码。

#### Scenario: 注册使用 BCrypt

- **WHEN** 用户注册
- **THEN** 使用 BCrypt 加密密码
- **AND** 密码哈希长度 60 字符

---

### Requirement: 双验证登录（C2）

现有用户登录时自动迁移密码到 BCrypt。

#### Scenario: MD5 用户登录自动升级

- **WHEN** 用户使用 MD5 密码登录成功
- **THEN** 自动将密码升级为 BCrypt 加密
- **AND** 后续登录使用 BCrypt 验证

---

### Requirement: 批量迁移（C3）

管理员可批量迁移所有 MD5 密码到 BCrypt。

#### Scenario: 批量迁移

- **WHEN** 管理员触发批量迁移
- **THEN** 将所有 MD5 密码转换为 BCrypt
- **AND** 迁移进度可查看

---

### Requirement: 仅支持 BCrypt（C4）

迁移完成后仅支持 BCrypt 验证。

#### Scenario: 清理 MD5 验证

- **WHEN** 所有用户密码已迁移
- **THEN** 移除 MD5 验证逻辑
- **AND** 仅支持 BCrypt 验证

---

## MODIFIED Requirements

### Requirement: 用户密码存储

用户密码使用 BCrypt 加密存储。

#### Scenario: 密码存储

- **WHEN** 用户注册/修改密码
- **THEN** 使用 BCrypt 加密存储

**变更前**: 使用 MD5 加密存储

**变更后**: 使用 BCrypt 加密存储

---

## REMOVED Requirements

无移除已有规格。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: BCrypt 密码加密 | 1.1, 1.3 |
| C2 | ADDED: 双验证登录 | 1.4 |
| C3 | ADDED: 批量迁移 | 2.1, 2.2 |
| C4 | ADDED: 仅支持 BCrypt | 2.1 |
