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

服务端对 BCrypt 与 MD5 两种存量格式均接受明文登录，并在 MD5 路径成功后自动升级。

#### Scenario: MD5 用户登录自动升级

- **WHEN** 用户使用 MD5 密码登录成功
- **THEN** 自动将密码升级为 BCrypt 加密
- **AND** 后续登录使用 BCrypt 验证

---

### Requirement: 密码传递口径统一为明文

注册、登录、修改密码、找回密码四条链路，客户端一律发送明文密码，由服务端统一承担加密存储与校验。

#### Scenario: 注册后可立即登录

- **WHEN** 用户用明文密码 P 注册成功（服务端存 `bcrypt(P)`）
- **THEN** 用户用同一明文 P 登录成功
- **AND** 登录成功后密码仍为 BCrypt 哈希（60 字符），未被改写为其他形式

#### Scenario: 客户端不得自行哈希

- **WHEN** 任一密码链路构造请求
- **THEN** `password` 字段为明文，不做 MD5 等客户端哈希
- **AND** 发送 `md5(P)` 的请求视为非法输入，登录返回密码错误（而非系统错误）

#### Scenario: 传输安全边界

- **WHEN** 客户端以明文发送密码
- **THEN** 系统不做传输层加密
- **AND** 部署方需自行提供 HTTPS 以获得密码传输保护（独立变更，不在本能力范围）

---

### Requirement: 存量库结构与基线对齐

密码升级所需的 DDL 必须有可执行迁移脚本，使存量库结构不落后于 `easychat.sql` 基线。

#### Scenario: password 列宽足够承载 BCrypt

- **WHEN** 在存量库（`user_info.password` 为 `varchar(32)`）上执行迁移
- **THEN** 该列被放宽为 `varchar(60)`
- **AND** 存量 MD5 账号登录成功时，自动升级写入 BCrypt 不再触发 `Data too long` 截断异常
- **AND** 登录接口不返回 500

#### Scenario: 基线变更必须配迁移

- **WHEN** 任一变更修改了表结构或列宽
- **THEN** 同批交付 `easychat-migration-<NNN>-*.sql` 脚本
- **AND** 基线 `easychat.sql` 与迁移脚本内容一致，不允许只改基线

#### Scenario: 迁移脚本幂等

- **WHEN** 同一迁移脚本被重复执行
- **THEN** 不报错、不破坏既有数据（建表使用 `IF NOT EXISTS`，列宽变更向后兼容）

---

### Requirement: 密码链路回归测试覆盖

BCrypt 登录 / 注册 / 改密路径必须有自动化回归用例覆盖。

#### Scenario: BCrypt 路径纳入回归

- **WHEN** 运行后端单元测试
- **THEN** 覆盖以下用例且全部通过：BCrypt 账号明文登录成功、注册后可登录、MD5 老账号登录并自动升级、BCrypt 账号拒绝 MD5 摘要登录
- **AND** 存在机控守卫断言「客户端不得自行哈希」与「冒烟脚本发送明文」

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
