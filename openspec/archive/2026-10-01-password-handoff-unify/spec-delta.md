# Spec Delta — 统一密码传递口径

- 关联 Tasks: 2026-10-01-password-handoff-unify/tasks.md
- 创建日期: 2026-10-01

> 扩展既有 `password-bcrypt` capability（登录 / 注册 / 改密 / 找回四条链路的取值口径）。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: 密码传递口径统一为明文（对齐 Capability C1）

注册、登录、修改密码、找回密码四条链路，客户端一律发送明文密码，由服务端统一承担加密存储与校验。

#### Scenario: 注册后可立即登录

- **WHEN** 用户用明文密码 P 注册成功（服务端存 `bcrypt(P)`）
- **THEN** 用户用同一明文 P 登录成功
- **AND** 登录成功后 `user_info.password` 仍为 BCrypt 哈希（60 字符），未被改写为其他形式

#### Scenario: 客户端不得自行哈希

- **WHEN** 任一密码链路构造请求
- **THEN** `password` 字段为明文，不做 MD5 等客户端哈希
- **AND** 发送 `md5(P)` 的请求视为非法输入，登录返回密码错误

#### Scenario: 四条链路口径一致

- **WHEN** 用户依次使用注册、登录、修改密码、找回密码
- **THEN** 四条链路的密码字段语义一致（均为明文），任一条成功不会导致其他条失败

---

### Requirement: 存量 MD5 账号无感登录并自动升级（对齐 Capability C2）

存量 MD5 密码账号在统一口径下仍可登录，登录成功时自动升级为 BCrypt。

#### Scenario: MD5 老账号登录并升级

- **WHEN** 存量账号密码为 `md5(明文)`，用户以明文登录
- **THEN** 服务端按 `md5(明文)` 比对通过，登录成功
- **AND** 该账号密码被自动重写为 BCrypt 哈希
- **AND** 下次登录按 BCrypt 验证通过

#### Scenario: 升级后拒绝 MD5 摘要登录

- **WHEN** 账号密码已升级为 BCrypt，客户端发送 `md5(明文)`
- **THEN** 登录失败，返回密码错误
- **AND** 不触发密码升级写库

---

### Requirement: 密码链路回归测试覆盖（对齐 Capability C3）

BCrypt 登录 / 注册 / 改密路径必须有自动化回归用例覆盖。

#### Scenario: BCrypt 路径纳入回归

- **WHEN** 运行后端单元测试
- **THEN** 覆盖以下用例且全部通过：BCrypt 账号登录成功、注册后登录成功、MD5 老账号登录并升级、BCrypt 账号拒绝 MD5 摘要登录
- **AND** 登录失败分支继续写入 `operation_log` 的 `LOGIN_FAILED` 记录

---

### Requirement: 存量库结构与基线对齐（对齐 Capability C4）

密码升级与 2026-09-30 之后的 IM 能力 DDL 必须有可执行的迁移脚本，使存量库结构不落后于 `easychat.sql` 基线。

#### Scenario: password 列宽足够承载 BCrypt

- **WHEN** 在存量库（`user_info.password` 为 `varchar(32)`）上执行迁移
- **THEN** 该列被放宽为 `varchar(60)`
- **AND** 存量 MD5 账号登录成功时，自动升级写入 BCrypt 不再触发 `Data too long` 截断异常

#### Scenario: 迁移补齐缺失表

- **WHEN** 在缺表的存量库上执行迁移
- **THEN** `emoji`、`favorite`、`user_status`、`operation_log` 四张表被创建
- **AND** 表结构（含索引、注释）与基线 `easychat.sql` 逐字一致

#### Scenario: 基线变更必须配迁移

- **WHEN** 任一变更修改了表结构或列宽
- **THEN** 同批交付 `easychat-migration-<NNN>-*.sql` 脚本
- **AND** 基线 `easychat.sql` 与迁移脚本内容一致，不允许只改基线

---

## MODIFIED Requirements

### Requirement: 双验证登录

服务端对 BCrypt 与 MD5 两种存量格式均接受明文登录，并在 MD5 路径成功后自动升级。

#### Scenario: MD5 用户登录自动升级

- **WHEN** 用户使用 MD5 密码登录成功
- **THEN** 自动将密码升级为 BCrypt 加密
- **AND** 后续登录使用 BCrypt 验证

**变更前（引用原 spec）**: 登录由客户端发送 MD5 摘要，服务端比对 `md5(摘要)`，与 BCrypt 路径口径分裂
**变更后**: 登录由客户端发送明文，服务端对 BCrypt 用 `matches(明文, hash)`、对 MD5 用 `md5(明文)` 比对，两条路径输入口径一致

---

## REMOVED Requirements

无。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 密码传递口径统一为明文 | 2.1, 2.2, 2.3 |
| C2 | ADDED: 存量 MD5 账号无感登录并自动升级 | 1.3, 2.5 |
| C3 | ADDED: 密码链路回归测试覆盖 | 1.1~1.5, 2.4 |
| C4 | ADDED: 存量库结构与基线对齐 | 1.6~1.9 |
| 已有 双验证登录 | MODIFIED: 双验证登录 | 2.1, 2.5 |
