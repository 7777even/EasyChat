# Proposal — BCrypt 哈希变体识别与 `matches()` 对齐

- 创建日期: 2026-10-04
- 效率等级: **L4**（命中 AGENTS §8「权限与认证」）

## Why

`PasswordEncoder` 的两个方法对**同一串密码**给出相反答案。

实测（`spring-security-crypto 5.6.0`，探针跑通后已删）：

```
默认生成前缀             = $2a$10$
matches("$2b$…" 改写)   = true      ← Spring 能校验
isBCrypt("$2b$…" 改写)  = false     ← 我们却判定「不是 BCrypt」
matches("$2y$…" 改写)   = true
isBCrypt("$2y$…" 改写)  = false
```

```java
// PasswordEncoder.java:49
public static boolean isBCrypt(String password) {
    return password != null && password.startsWith("$2a$");   // 只认 $2a$
}
```

`matches()` 用的正是同一个 `BCryptPasswordEncoder`，它接受多个 modular-crypt 变体；
`isBCrypt()` 把它们全判成「非 BCrypt」。二者不一致，即为缺陷。

`UserInfoServiceImpl` 四处调用全部走错分支（后果见 design.md ADR-001）。

**当前不是线上故障**：Spring 5.6.0 只生成 `$2a$`。但这是「一次依赖升级 / 一次数据导入 /
一次跨系统账号迁移」即可翻转的陷阱，且 AGENTS §6.2-2 已把「`$2a$` 开头」写进规范。

## What Changes

- 后端:
  - `PasswordEncoder#isBCrypt`：由「只认 `$2a$`」放宽为接受 BCrypt 全部已知变体前缀
    （`$2a$` / `$2b$` / `$2x$` / `$2y$`）
  - 新增 `PasswordEncoderTest`：锁定「`isBCrypt` 不得比 `matches` 更严」
- 前端: 无改动
- 数据库: 无表结构变更，无存量数据变更

## Capabilities

- C1: `isBCrypt` 覆盖 BCrypt 全部已知变体前缀，与 `matches()` 判定口径一致
- C2: MD5 等非 BCrypt 哈希仍被正确识别为非 BCrypt（不得放宽过头）
- C3: 密码哈希判定具备自动化回归，且断言口径由 Spring 行为**实测推导**而非硬编码

## Impact

- 对外接口: **无变更**（`Result<T>` / 错误码 / 路由均不动）
- 存量数据: **零影响**。现网哈希全为 `$2a$`，新旧实现判定结果完全相同；
  MD5 旧账号为 32 位十六进制，两种实现都判非 BCrypt
- 行为变化: 仅当库中出现 `$2b$` / `$2x$` / `$2y$` 哈希时，
  由「走 MD5 分支 → 登录失败 + 双重加密」改为「正确走 BCrypt 分支」
- 性能: 无（`isBCrypt` 仍是前缀字符串比较）
- 回滚方案: 单方法 revert，无数据迁移、无配置项

## 人工确认关卡

> 本提案经 _________（角色/姓名） 于 2026-10-04 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
