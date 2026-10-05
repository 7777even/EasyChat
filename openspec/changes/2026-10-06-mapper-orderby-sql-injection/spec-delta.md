# Spec Delta — 收口 Mapper 的 `${}` 字符串拼接与管理端查询对象的请求绑定面

- 关联 Tasks: `2026-10-06-mapper-orderby-sql-injection/tasks.md`
- 创建日期: 2026-10-06
- 目标 capability: `openspec/specs/sql-safety/spec.md`（**新建**）

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities 一一对应。

## ADDED Requirements

### Requirement: Mapper 层禁止字符串拼接（对齐 Capability C1）

Mapper XML 中不得出现任何 `${}` 形式的字符串拼接；所有可变输入必须通过 `#{}` 参数绑定，
排序等结构性 SQL 片段必须写为 XML 内字面量。

#### Scenario: Mapper XML 中出现 `${}`

- **WHEN** 任一 `com/easychat/mappers/*.xml` 在剥除 XML 注释后仍包含 `${`
- **THEN** `verify_sql_concat_guard.mjs` 报 FAIL，列出 `文件名:行号` 与该行原文
- **AND** 退出码为 1，阻断提交 / 推送

#### Scenario: 解析器失配导致扫不到任何文件

- **WHEN** 门禁实扫到的 Mapper XML 少于 10 个（路径变更、目录被移走等）
- **THEN** 报 FAIL 而不是「零违规通过」
- **AND** 报出自检项「解析器能识别 `${}`」失败

> **为何要有这条 Scenario**：首版正则写成「反引号可选 + 反引号必需」的组合，
> 导致 `ADD COLUMN seq`、`order by ${query.orderBy}` 这类**不带反引号**的标识符匹配不到，
> 门禁因「没扫到任何 DDL」而**空转通过**（AGENTS §2.1 第 14 条）。
> **断言通过 ≠ 断言在做事。**

#### Scenario: 注释中说明 `${}` 的用法

- **WHEN** `${}` 只出现在 XML 注释里（作为反例说明）
- **THEN** 不算违规
- **AND** 报出的行号与源文件**真实行号一致**（剥注释时保留换行）

---

### Requirement: 列表端点的查询字段白名单化（对齐 Capability C2）

可从 HTTP 请求绑定的查询对象，其字段必须是显式声明的白名单；
调用方**不得**通过请求参数指定排序表达式或任意 WHERE 条件。

#### Scenario: 请求携带 orderBy 参数

- **WHEN** 调用方对管理端列表端点传入 `orderBy=<任意字符串>`
- **THEN** 该参数**不参与** SQL 构造（绑定器无该字段可写）
- **AND** 端点按 Service / Mapper 内硬编码的固定排序返回结果
- **AND** 不抛异常、不返回错误码（该参数被静默忽略）

#### Scenario: 请求携带未声明的查询字段

- **WHEN** 调用方传入白名单之外的字段名
- **THEN** 该字段被 Spring 绑定器忽略，不进入任何 SQL 条件

---

### Requirement: 凭据字段不得出现在查询对象与 HTTP 面（对齐 Capability C3）

`password` / `passwordFuzzy` 等凭据相关字段不得作为查询条件存在，
更不得可从 HTTP 请求到达 —— 存量 MD5 账号的哈希无盐且确定，
对其做 `LIKE` 匹配等价于**明文口令猜测预言机**。

#### Scenario: 请求携带 passwordFuzzy

- **WHEN** 调用方对 `/admin/loadUser` 传入 `passwordFuzzy=<MD5(猜测)>`
- **THEN** 该字段不参与 SQL 构造
- **AND** `UserInfoMapper.xml` 中不存在任何以 `password` 列为条件的 `<if>`

#### Scenario: 出参含 PO 的 password 字段

- **WHEN** 管理端用户列表返回 `PaginationResultVO<UserInfo>`（PO 而非 VO）
- **THEN** `password` 因 `UserInfo.password` 上的 `@JsonIgnore` **不参与序列化**
- **AND** 客户端拿不到任何形式的密码哈希

> **本 Scenario 是「已存在的保证」而非新增**：2026-10-06 盘点时我曾据
> `base_column_list` 含 `password` 判定「哈希外泄」，读到 `@JsonIgnore` 后**证伪并撤回**。
> 此处固化为规格，防止后续维护者重复误判，也提醒 PO/VO 混用本身仍是待处理的规范偏离。

---

### Requirement: SQL 拼接禁令由机控强制且门禁自身可证（对齐 Capability C4）

上述三条必须由门禁持续强制，且门禁自身的判别力须由变异检验证明。

#### Scenario: 有人把字面量排序改回 `${}` 拼接

- **WHEN** 任一 Mapper 的排序被改回 `order by ${query.orderBy}`
- **THEN** `verify_sql_concat_guard.mjs` 退出码 1
- **AND** `mutation_sql_concat_guard.cjs` 中对应的变异被记为「捕获」

#### Scenario: 门禁被接入 CI

- **WHEN** `verify_sql_concat_guard.mjs` 被写入 `.github/workflows/ci.yml` 或 `pre-push`
- **THEN** 必须已存在「基线自检 exit 0」与「反例转红」两条实跑记录
- **AND** AGENTS §10 该行不再标注「暂未接入」

#### Scenario: 已登记的不可静态检测项

- **WHEN** 需要判断某个 `${}` 是否真的可从 HTTP 到达
- **THEN** 门禁**一律阻断**，不做「按可达性分级放行」
- **AND** 该盲区按三字段登记：盲区 + 兜底手段（变异检验 / 活体注入实测）+ 兜底手段的实测证据

> **为何「一律阻断」而不分级**：分级需要跨 Controller / Service 的可达性推断，
> 而这正是 AGENTS §2.1 第 12 条点名的高危动作（局部证据推断系统行为）。

---

## MODIFIED Requirements

**无。** 本变更不修改任何已有 capability 的规格。

> 说明：`orderBy` 曾是「事实存在但从未被任何规格描述过」的行为 ——
> `openspec/specs/` 下无任何 spec 提及调用方可指定排序。
> 故此处不是 MODIFIED 而是 ADDED（新增一条「它现在被禁止」的规格）。

## REMOVED Requirements

**无。** 无 capability 被移除。

> 但**移除了一项事实能力**：调用方自定义排序。ADR-001 已把它记为需人工拍板的取舍点。
> 若人工决定保留该能力，本节需改为 REMOVED 并在 spec 中写明枚举白名单形态。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: Mapper 层禁止字符串拼接 | 阶段零.1、阶段一.1~3 |
| C2 | ADDED: 列表端点的查询字段白名单化 | 阶段二.1~3 |
| C3 | ADDED: 凭据字段不得出现在查询对象与 HTTP 面 | 阶段一.3、阶段二.1~2 |
| C4 | ADDED: SQL 拼接禁令由机控强制且门禁自身可证 | 阶段零.1~2、阶段三、阶段四 |
