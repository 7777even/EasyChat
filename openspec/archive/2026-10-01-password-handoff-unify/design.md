# Design — 统一密码传递口径

- 关联 Proposal: 2026-10-01-password-handoff-unify/proposal.md
- 创建日期: 2026-10-01

## 1. 架构设计

改造后四条链路的统一形态：**客户端发送明文 → 服务端 BCrypt 编码/校验**。

```
注册    Login.vue(明文) → POST /account/register  → UserInfoServiceImpl.register  → bcrypt(明文) 入库
登录    Login.vue(明文) → POST /account/login     → UserInfoServiceImpl.login
                                                          ├─ 存量是 BCrypt → matches(明文, hash)         ✓
                                                          └─ 存量是 MD5    → md5(明文)==hash → 升级为 BCrypt ✓
改密    UserInfoPassword.vue(明文) → POST /userInfo/updatePassword → 双验证旧密码 → bcrypt(新明文)
找回    Login.vue(明文) → POST /account/resetPassword → bcrypt(新明文)
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | 无 | — |
| Service | 无（BCrypt + MD5 双验证 + 自动升级已完备） | 业务规则保持单一真源 |
| Mapper / SQL | 无 | — |
| Entity | 无 | — |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 主进程 | 无 | — |
| preload | 无 | — |
| 渲染进程 | `views/Login.vue`：登录分支去 `md5()`、删 `js-md5` import | 组件内不引入与业务无关的加密实现 |

## 2. 接口设计

无对外接口签名变更。`password` 字段语义由「MD5 摘要」变为「明文」，需在 spec 中显式固化，避免下次再被单方面改动。

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/account/register` | POST | `UserRegisterDTO{email,nickName,password(明文),checkCode,checkCodeKey}` | `Result<Void>` | 匿名 |
| `/api/account/login` | POST | `UserLoginDTO{email,password(明文),checkCode,checkCodeKey}` | `Result<UserInfoVO>` | 匿名 |
| `/api/userInfo/updatePassword` | POST | `oldPassword(明文), password(明文)` | `Result<Void>` | 登录用户 |
| `/api/account/resetPassword` | POST | `email,code,newPassword(明文)` | `Result<Void>` | 匿名 |

### 错误码

无新增。复用 `CODE_2103`（密码错误）/ 登录失败统一 `账号或者密码错误`。

## 3. 数据模型

原判断为「无变更」，活体验证后修正如下（存量库与基线存在实打实的差集）：

| 表 | 变更 | 字段 | 类型 | 存量库现状 | 基线 `easychat.sql` |
|----|------|------|------|------------|---------------------|
| `user_info` | 列宽放宽 | `password` | `varchar(32)` → `varchar(60)` | `varchar(32)` | `varchar(60)` |
| `emoji` | **缺表**（新建） | 全量 | 见基线 | 不存在 | 已存在 |
| `favorite` | **缺表**（新建） | 全量 | 见基线 | 不存在 | 已存在 |
| `user_status` | **缺表**（新建） | 全量 | 见基线 | 不存在 | 已存在 |
| `operation_log` | **缺表**（新建） | 全量 | 见基线 | 不存在 | 已存在 |

> 迁移脚本落 `easychat-migration-010-password-and-im-tables.sql`，只补差集、不改基线已有结构；`easychat.sql` 已是最新基线，无需再改。
> 已核对**无需**迁移的部分：`chat_message.delete_flag`、`chat_session_user.top_type/no_disturb/draft`、`call_log`、`group_file`、`report_audit_log`、`sensitive_word.delete_flag` 均已就位。

## 4. 安全设计

- 鉴权: 四个端点均为匿名可访问，登录态由 Token + Redis 会话承担，本变更不触碰。
- 数据权限: 不涉及资源归属。
- 输入校验: `@Valid` + 正则（数字+字母，8-18 位）保持不变，`@NotEmpty` 保持不变。
- SQL 注入防护: 不涉及 SQL 变更。
- **传输安全边界（必须在 QA 与 spec 中显式声明）**：当前 API 无 TLS，客户端 MD5 不提供机密性（攻击者可见明文请求体即可重放），移除后安全性实质不变。真正的密码传输保护需部署 HTTPS，属独立变更。
- **登录失败日志**：`login` 失败分支已写 `operationLogService.recordLog(..., "LOGIN_FAILED", ...)`，本变更后该日志将真正开始产生（当前所有登录都失败，日志里全是密码错误），复盘时需知悉。

## 5. ADR

### ADR-001: 选择「前端发明文 + 服务端 BCrypt」而非「服务端兼容双口径」

- 状态: 已接受
- 上下文: 后端要兼容两种客户端口径（明文 / md5），还是让前端对齐后端？
- 决策: 改前端。后端零改动，口径唯一。
- 后果:
  - 正: 服务端保持单一密码口径，长期无技术债；老账号自动升级路径本就存在；回退成本极低。
  - 负: 需同步修正 7 个冒烟脚本；已分发的旧版客户端若不升级则会登录失败（桌面端随包发布，版本可控，风险可接受）。

### ADR-002: 拒绝「统一为前端先 MD5、服务端 bcrypt(md5(x))」

- 状态: 已拒绝
- 上下文: 保持现有冒烟脚本与前端调用方式不变，改动面最小。
- 决策: 拒绝。
- 后果: bcrypt 前做一次无盐快哈希是反模式（等价于把密码降级到 128 位无盐摘要后再慢哈希），且需要把注册/改密/找回三条链路全部改成先 MD5，改动面反而更大。

### ADR-003: 本变更不删除 `js-md5` 依赖项

- 状态: 已接受
- 上下文: `js-md5` 移除使用点后将无人引用。
- 决策: 仅移除使用点，不动 `package.json`。
- 后果: 留一处未使用依赖，属已知残留；删除依赖属前端 L3，需另开变更。

### ADR-004: 不删除 `StringTools.encodeByMD5`

- 状态: 已接受
- 上下文: 该工具同时用于 token 生成、用户 ID / 群 ID 拼接等非密码场景。
- 决策: 保留工具方法，仅密码校验路径的双验证逻辑不动。
- 后果: MD5 在代码库中仍然存在，但用途已收敛到非密码场景，不再是密码安全债。

### ADR-005: 补写 migration-010 并在本机执行，而非只改基线

- 状态: 已接受（活体验证后追加）
- 上下文: 2026-09-30 之后的 DDL（`emoji`/`operation_log`/`favorite`/`user_status` 四表 + `password` 列宽）**只写进了 `easychat.sql` 基线**，没有对应迁移脚本，也没在存量库执行。结果是：基线看起来没问题，存量库却缺 5 项结构，其中 `password` 列宽直接让登录返回 500。这是「基线即真相」被误当成「存量库已对齐」导致的隐性故障——单测与 mock 全都发现不了，只有真连库才暴露。
- 决策: 本变更补 `easychat-migration-010-password-and-im-tables.sql`（只补差集）并对本机 `easychat` 库执行；基线不动。
- 后果:
  - 正: 存量库恢复可用；后续「改了基线就必须配迁移」有据可依。
  - 负: 需人工确认 DB 结构变更（L4）；仍存在其他环境未执行的历史迁移的风险，需要一次全环境对账（不在本变更范围）。

### ADR-006: 存量 `md5(md5(明文))` 账号不纳入本变更修复

- 状态: 已接受
- 上下文: 核查 `e0b139f~1` 版本发现，BCrypt 改造**之前**的 `register` 存的是 `md5(md5(明文))`（客户端先 md5、服务端再 md5），而当时 `login` 是直接比对收到的值——即**「注册后无法登录」在 BCrypt 之前就已存在**。这类账号既非 `md5(明文)` 也非 BCrypt，无法被双验证命中，且无法从双重哈希反推明文。
- 决策: 本变更不处理这类账号，不实现「猜测双哈希」的兼容分支（会把协议搞得更乱）；其恢复路径是邮箱找回密码（改密走明文 → 落 BCrypt）。
- 后果: 这类存量账号在迁移后仍需用户自行找回密码；本机实测两个账号存的是 `md5(明文)` 单哈希，属可自动升级的正常存量。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 漏改冒烟脚本导致 QA 误判为「登录又坏了」 | 高 | 中 | 7 个脚本逐个改为明文，并在 QA 中列出实际执行结果 |
| 忘记执行迁移脚本，登录仍 500 | 中 | 高 | 迁移脚本与代码同批交付；QA 中给出执行前后 `SHOW COLUMNS` 对照输出作为证据 |
| 其他环境仍停留在旧结构 | 中 | 中 | QA 中记录「需全环境对账」为遗留项，后续单独处理 |
| 存量 MD5 账号升级后无法回退到 MD5 校验 | 低 | 低 | 双验证路径保留，随时可停用升级逻辑 |
| 旧版已安装客户端升级后无法登录 | 低 | 中 | 桌面端整包升级；如已分发需同步公告，QA 中记录 |
| 回归单测仍只覆盖 MD5 老路径 | 中 | 高 | 阶段一强制 [TDD] 先写 BCrypt 登录/注册/改密三组用例并先跑红 |

## 7. 依赖与前提

- 无前置 Change 依赖。
- 前提：`password-bcrypt` 的服务端改造已合入主干（本变更基于其现状，不重复改造）。
- 活体验证需要 MySQL + Redis + 至少一个存量 MD5 账号，QA 中如实记录是否具备。
