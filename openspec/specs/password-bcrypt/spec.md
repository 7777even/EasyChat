# Spec — MD5 → BCrypt 密码加密升级

## ADDED Requirements

### Requirement: BCrypt 密码加密

新注册用户使用 BCrypt 加密密码。

#### Scenario: 注册使用 BCrypt

- **WHEN** 用户注册
- **THEN** 使用 BCrypt 加密密码
- **AND** 密码哈希长度 60 字符

---

### Requirement: 双验证登录

现有用户登录时自动迁移密码到 BCrypt。

#### Scenario: MD5 用户登录自动升级

- **WHEN** 用户使用 MD5 密码登录成功
- **THEN** 自动将密码升级为 BCrypt 加密
- **AND** 后续登录使用 BCrypt 验证

---

### Requirement: 修改密码使用 BCrypt

用户修改密码时使用 BCrypt 加密。

#### Scenario: 修改密码

- **WHEN** 用户修改密码
- **THEN** 使用 BCrypt 加密新密码
- **AND** 旧密码验证支持 MD5/BCrypt 双验证

---

## MODIFIED Requirements

### Requirement: 用户密码存储

用户密码使用 BCrypt 加密存储。

#### Scenario: 密码存储

- **WHEN** 用户注册/修改密码
- **THEN** 使用 BCrypt 加密存储

**变更前**: 使用 MD5 加密存储

**变更后**: 使用 BCrypt 加密存储
