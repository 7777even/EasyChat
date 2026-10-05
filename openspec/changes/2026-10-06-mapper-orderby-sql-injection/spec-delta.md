# Spec Delta — 收口 Mapper 的 `${}` 字符串拼接，并把排序改为列名枚举白名单

- 关联 Tasks: `2026-10-06-mapper-orderby-sql-injection/tasks.md`
- 创建日期: 2026-10-06
- 目标 capability: `openspec/specs/sql-safety/spec.md`（**新建**）

> 格式对齐 `openspec/specs/<capability>/spec.md`。
> 与 proposal Capabilities 一一对应（C1~C5）。

## ADDED Requirements

### Requirement: Mapper 层禁止字符串拼接（对齐 Capability C1）

Mapper XML 中不得出现任何 `${}` 形式的字符串拼接；所有可变输入必须通过 `#{}` 参数绑定，
排序等结构性 SQL 片段必须写为 XML 内字面量（可为 `<choose>` 枚举分支）。

#### Scenario: Mapper XML 中出现 `${}`

- **WHEN** 任一 `com/easychat/mappers/*.xml` 在剥除 XML 注释后仍包含 `${`
- **THEN** `verify_sql_concat_guard.mjs` 报 FAIL，列出 `文件名:行号` 与该行原文
- **AND** 退出码为 1，阻断提交 / 推送

#### Scenario: 解析器失配导致扫不到任何文件

- **WHEN** 门禁实扫到的 Mapper XML 少于 10 个（路径变更、目录被移走等）
- **THEN** 报 FAIL 而不是「零违规通过」
- **AND** 报出自检项「解析器能识别 `${}`」失败

> **为何要有这条 Scenario**：首版正则写成「反引号可选 + 反引号必需」的组合，
> 导致不带反引号的标识符匹配不到，门禁因「没扫到任何 DDL」而**空转通过**
> （AGENTS §2.1 第 14 条）。**断言通过 ≠ 断言在做事。**

#### Scenario: 注释中说明 `${}` 的用法

- **WHEN** `${}` 只出现在 XML 注释里（作为反例说明）
- **THEN** 不算违规
- **AND** 报出的行号与源文件**真实行号一致**（剥注释时保留换行）

---

### Requirement: 排序走列名枚举白名单，能力保留但不可指定任意串（对齐 Capability C2）

调用方**仍可指定排序**，但只能取自代码内的列名枚举白名单；
未命中白名单的值一律回退默认排序项，不报错、不拼接。

#### Scenario: 请求指定白名单内的排序

- **WHEN** 调用方传 `sortField` / `sortDirection`，且组合命中枚举白名单
- **THEN** 按该枚举项对应的**固定 SQL 片段**排序
- **AND** 该片段是 XML 内字面量，不含任何运行时字符串

#### Scenario: 请求指定白名单外的排序

- **WHEN** 调用方传 `sortField=(select 1 from information_schema.tables)`
      或 `sortField=id desc`（含空格与方向）或空串
- **THEN** 回退默认排序项
- **AND** 不抛异常、不返回错误码
- **AND** 该值**不出现**在任何 SQL 片段中

#### Scenario: 枚举项与 XML 分支数量不匹配

- **WHEN** 排序枚举新增一项但 XML 的 `<choose>` 未加对应 `<when>` 分支（或反之）
- **THEN** `verify_sql_concat_guard.mjs` 报 FAIL（断言枚举项数 == 分支数）
- **AND** 退出码为 1

> **为何要有这条 Scenario**：ADR-001 保留了排序能力，代价是新增
> 「枚举 ↔ XML 分支」这个**需要维护的契约**；少一个分支即该排序项**静默失效**
> （不报错、只是排序不生效），是最难发现的一类退化。

#### Scenario: 保留的 `orderBy` 字段

- **WHEN** 调用方仍传 `orderBy=<任意字符串>`（旧参数）
- **THEN** 该参数**不参与** SQL 构造（setter 已收窄为包内可见，外部不可写）

---

### Requirement: 凭据字段不得存在于查询对象（对齐 Capability C3）

`password` / `passwordFuzzy` 等凭据相关字段**不得**作为查询条件存在 ——
存量 MD5 账号的哈希无盐且确定，对其做 `LIKE` 匹配等价于**明文口令猜测预言机**。

#### Scenario: 请求携带 passwordFuzzy

- **WHEN** 调用方对 `/admin/loadUser` 传 `passwordFuzzy=<MD5(猜测)>`
- **THEN** 该字段在 `UserInfoQuery` 中**不存在**，绑定器无从写入
- **AND** `UserInfoMapper.xml` 中不存在任何以 `password` 列为条件的 `<if>`

#### Scenario: 出参含 PO 的 password 字段

- **WHEN** 管理端用户列表返回 `PaginationResultVO<UserInfo>`（PO 而非 VO）
- **THEN** `password` 因 `UserInfo.password` 上的 `@JsonIgnore` **不参与序列化**
- **AND** 客户端拿不到任何形式的密码哈希

> **本 Scenario 是「已存在的保证」而非新增**：2026-10-06 盘点时我曾据
> `base_column_list` 含 `password` 判定「哈希外泄」，读到 `@JsonIgnore` 后**证伪并撤回**。
> 此处固化为规格，防止后续维护者重复误判，也提醒 PO/VO 混用本身仍是待处理的规范偏离。

---

### Requirement: 分页列表恒有 ORDER BY（对齐 Capability C4）

所有走 `BaseMapper.selectList` + 分页的列表查询，在任何入参组合下都必须带确定性排序。

#### Scenario: 调用方未指定排序

- **WHEN** `/admin/loadGroup` 或 `/admin/loadBeautyAccountList` 未传 `sortField`
- **THEN** 使用该端点的**默认排序项**（不是「无 ORDER BY」）

#### Scenario: 翻页结果稳定性

- **WHEN** 对同一页码与同一筛选条件重复请求 3 次
- **THEN** 三次返回的行序完全一致（无重复行、无漏行）

> **为何要有这条 Scenario**：收口前这两个端点在 `orderBy` 为空时走 `<if>` 的 else 分支，
> 即**完全没有 ORDER BY**；MySQL 不保证无 ORDER BY 时的稳定输出，
> 翻页时可能同一行落在两页或某行不出现。**这是本 Change 顺带修掉的既有缺陷。**

---

### Requirement: SQL 拼接禁令由机控强制且门禁自身可证（对齐 Capability C5）

上述四条必须由门禁持续强制，且门禁自身的判别力须由变异检验证明。

#### Scenario: 有人把枚举分支改回 `${}` 拼接

- **WHEN** 任一 Mapper 的 `<choose>` 被改回 `order by ${query.orderBy}`
- **THEN** `verify_sql_concat_guard.mjs` 退出码 1
- **AND** `mutation_sql_concat_guard.cjs` 中对应的变异被记为「捕获」

#### Scenario: 门禁被接入 CI

- **WHEN** `verify_sql_concat_guard.mjs` 被写入 `.github/workflows/ci.yml` 或 `pre-push`
- **THEN** 必须已存在「基线自检 exit 0」与「反例转红」两条实跑记录
- **AND** AGENTS §10 该行不再标注「当前 exit=1，暂未接入」

#### Scenario: 已登记的不可静态检测项

- **WHEN** 需要判断某个 `${}` 是否真的可从 HTTP 到达
- **THEN** 门禁**一律阻断**，不做「按可达性分级放行」
- **AND** 该盲区按三字段登记：盲区 + 兜底手段（变异检验 / 活体注入实测）+ 兜底手段的实测证据

> **为何「一律阻断」而不分级**：分级需要跨 Controller / Service 的可达性推断，
> 而这正是 AGENTS §2.1 第 12 条点名的高危动作。本轮我恰好在该推断上栽了两次 ——
> 一次把「3 个端点可达」说成事实（实际 2 个，端点 1 在 Service 层已被覆盖排序），
> 一次把「PO 出参泄露哈希」说成事实（实际有 `@JsonIgnore`）。

---

## MODIFIED Requirements

**无。** 本变更不修改任何已有 capability 的规格。

> 说明：`orderBy` 曾是「事实存在但从未被任何规格描述过」的行为 ——
> `openspec/specs/` 下无任何 spec 提及调用方可指定排序。
> 故此处不是 MODIFIED 而是 ADDED（新增一条「它现在必须走白名单」的规格）。

## REMOVED Requirements

**无 capability 被移除。**

> 初版曾提议移除「调用方自定义排序」这一能力（ADR-001），
> **人工决策为保留**，故本节为空。若将来改回移除方案，需在此声明并写明替代能力。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: Mapper 层禁止字符串拼接 | 阶段零.1~2、阶段二.1~2 |
| C2 | ADDED: 排序走列名枚举白名单 | 阶段一全部、阶段二.1、阶段三.1~2 |
| C3 | ADDED: 凭据字段不得存在于查询对象 | 阶段二.3、阶段三.1 |
| C4 | ADDED: 分页列表恒有 ORDER BY | 阶段三.3、阶段五（翻页稳定性） |
| C5 | ADDED: SQL 拼接禁令由机控强制且门禁自身可证 | 阶段零.1~4、阶段四、阶段五 |
