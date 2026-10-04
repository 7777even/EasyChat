# Spec Delta — BCrypt 哈希变体识别与 `matches()` 对齐

- 目标 capability: `password-bcrypt`（**修改既有**，不新建）
- 创建日期: 2026-10-04

## ADDED Requirements

### Requirement: BCrypt 格式判定与校验口径一致

密码哈希的**格式判定**（`isBCrypt`）不得比**校验**（`matches`）更严：凡校验器能够接受的
BCrypt 哈希，格式判定必须认定其为 BCrypt。

#### Scenario: 校验器接受的变体不被误判为非 BCrypt

- **WHEN** 待判定哈希的变体前缀属于 BCrypt 已知变体集合（`$2a$` / `$2b$` / `$2x$` / `$2y$`）
- **THEN** `isBCrypt` 返回 `true`
- **AND** 密码链路据此走 BCrypt 分支，**不得**落到 MD5 双验证分支

#### Scenario: 判定方向的安全边界

- **WHEN** `isBCrypt(p)` 返回 `true` 但校验器无法验证 `p`
- **THEN** 视为密码错误，登录/改密失败
- **AND** **不修改库中密码**（已加密的哈希不得被当作明文再次加密）

#### Scenario: 不得漏放校验器已支持的变体

- **WHEN** 依赖升级后校验器新增支持某个 BCrypt 变体
- **THEN** 回归测试自动转红，提示补全放行集合
- **AND** 该断言由**校验器实测行为推导**得出，不依赖硬编码的版本清单

#### Scenario: 判定不得放宽到非 BCrypt 数据

- **WHEN** 待判定值为 MD5 哈希（32 位十六进制）、`$1$` 系列、
      `$2$`（无 minor 版本号）或 `$20$`~`$29$`
- **THEN** `isBCrypt` 返回 `false`
- **AND** 空值、空串亦返回 `false`

#### Scenario: 截断串不得被误判为 BCrypt

- **WHEN** 待判定值前缀形似 BCrypt 但**长度不等于 60**
      （如 `"$2a$"`、`"$2a$10$tooshort"`）
- **THEN** `isBCrypt` 返回 `false`
- **AND** 该判定方向是安全的：调用方落到 MD5 分支并校验失败，**不修改库中密码**

> BCrypt modular-crypt 格式长度恒为 60，故长度校验属格式识别的必要组成部分，
> 而非启发式阈值。首版实现（仅前缀匹配）会把上述截断串误判为 BCrypt，
> 该缺陷由 TDD 红阶段发现，见 design.md ADR-004。

---

## MODIFIED Requirements

### Requirement: BCrypt 密码加密

新注册用户使用 BCrypt 加密密码。

#### Scenario: 注册使用 BCrypt

- **WHEN** 用户注册
- **THEN** 使用 BCrypt 加密密码
- **AND** 密码哈希长度 60 字符
- **AND** 哈希前缀为校验器当前默认生成的 BCrypt 变体

**变更前**: （无变体前缀约束）

**变更后**: 新增「前缀为校验器默认变体」这一**观测口径**——
注意这**不是**把前缀钉死为 `$2a$`。`$2a$` 是 Spring Security 5.6.0 的当前默认输出，
属实现细节而非契约；契约是「长度为 60 的 BCrypt modular-crypt 格式」。

> **为何要显式声明这一点**：AGENTS §6.2-2 曾把「`$2a$` 开头」写进规范，
> 使实现误以为前缀是契约、从而写出只认单一前缀的判定函数。
> 该表述已一并更正。

---

### Requirement: 双验证登录

服务端对 BCrypt 与 MD5 两种存量格式均接受明文登录，并在 MD5 路径成功后自动升级。

#### Scenario: MD5 用户登录自动升级

- **WHEN** 用户使用 MD5 密码登录成功
- **THEN** 自动将密码升级为 BCrypt 加密
- **AND** 后续登录使用 BCrypt 验证

#### Scenario: 已是 BCrypt 的密码不得被二次加密

- **WHEN** 用户以任意 BCrypt 变体的密码登录成功
- **THEN** **不触发**「MD5 → BCrypt 自动升级」分支
- **AND** 库中密码保持原哈希不变

**变更前**: 自动升级分支以 `!isBCrypt(hash)` 为条件，
而 `isBCrypt` 仅认单一前缀，导致非该前缀的 BCrypt 哈希**被二次加密**（不可逆损坏）

**变更后**: 自动升级分支以「判定为非 BCrypt」为条件，
且判定口径已与校验器对齐，故已是 BCrypt 的密码（含全部变体）不会进入升级分支

> 这是本变更**唯一涉及不可逆数据损坏**的路径，见 design.md §2。

---

## REMOVED Requirements

（无）
