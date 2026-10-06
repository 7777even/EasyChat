# sql-safety

> SQL 层安全契约：拼接禁令、排序白名单、凭据字段隔离。
>
> 首次建立于 2026-10-06（`openspec/changes/2026-10-06-mapper-orderby-sql-injection`，
> L4）。起因是一次只读盘点发现 17 处 `order by ${query.orderBy}`，
> 其中 2 个管理端端点的 `orderBy` 可由请求参数直达。

## ADDED Requirements

### Requirement: Mapper 层禁止字符串拼接

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

#### Scenario: 注释中说明 `${}` 的用法

- **WHEN** `${}` 只出现在 XML 注释里（作为反例说明）
- **THEN** 不算违规
- **AND** 报出的行号与源文件**真实行号一致**（剥注释时保留换行）

---

### Requirement: 排序走列名枚举白名单，能力保留但不可指定任意串

调用方**仍可指定排序**，但只能取自代码内的列名枚举白名单（`SortOption`）；
**未命中白名单的值一律显式报错**（`CODE_1001`），不静默回退、不拼接。

#### Scenario: 请求指定白名单内的排序

- **WHEN** 调用方传 `sortField` / `sortDirection`，且组合命中枚举白名单
- **THEN** 按该枚举项对应的**固定 SQL 片段**排序
- **AND** 该片段是 XML 内字面量，不含任何运行时字符串

#### Scenario: 请求指定白名单外的排序

- **WHEN** 调用方传 `sortField=(select group_id from group_info limit 1)`、
      `sortField=id desc`（含空格与方向）、`sortDirection=desc; drop table user_info`
      或 `sortDirection=(select sleep(3))`
- **THEN** 返回错误码 **`CODE_1001`（参数非法）**
- **AND** **不**静默回退到默认排序
- **AND** 该值**不出现**在任何 SQL 片段中
- **AND** 请求**不抵达** Mapper（实测用 `ArgumentCaptor` 断言 `selectList` 从未被调用）

> **为何不静默回退**：初版提议静默回退，人工决策改为显式报错。收益是调用方传错排序
> **立即可见**，且「枚举 ↔ XML 分支」契约被破坏时会更早暴露（否则表现为「排序静默失效」）。
> 前端当前**不发送**排序参数（grep 零命中），故该路径现状下不会被触发。

#### Scenario: 枚举项与 XML 分支数量不匹配

- **WHEN** 排序枚举新增一项但 XML 的 `<choose>` 未加对应 `<when>` 分支（或反之）
- **THEN** `verify_sql_concat_guard.mjs` 报 FAIL（断言「每个枚举项都被 `<when>` 或 `<otherwise>` 覆盖」）
- **AND** 退出码为 1

> **为何要有这条 Scenario**：ADR-001 保留了排序能力，代价是新增
> 「枚举 ↔ XML 分支」这个**需要维护的契约**；少一个分支即该排序项**静默失效**
> （不报错、只是排序不生效），是最难发现的一类退化。

#### Scenario: 裸字面量排序未入白名单

- **WHEN** 任一 Mapper 出现不在 `SortOption` 内的裸字面量排序
      （如 `ORDER BY create_time DESC`）
- **THEN** 报 FAIL，提示「新增排序须先加枚举」

> **本 Scenario 来自一次真实发现**：`EmojiMapper.xml` 的 `ORDER BY create_time DESC`
> 从未进入过任何治理 —— 因为早期断言只扫 `${}` 形态，漏掉了非 `${}` 的字面量。
> 即「扫 `${}`」本身不足以覆盖全部排序面。

#### Scenario: 分支串表

- **WHEN** 某 Mapper 的 `<when>` 引用了**属于其他表**的枚举项
- **THEN** 报 FAIL，并指出该文件实际对应的表名

#### Scenario: 服务端已预设的排序项

- **WHEN** 内部调用方已显式 `setSortOption(...)`，且请求未带 `sortField` / `sortDirection`
- **THEN** 保留预设值，**不**被该表默认项覆盖

---

### Requirement: 分页列表恒有 ORDER BY

所有走 `BaseMapper.selectList` + 分页的列表查询，在任何入参组合下都必须带确定性排序。

#### Scenario: 调用方未指定排序

- **WHEN** 请求未带 `sortField` / `sortDirection`
- **THEN** 使用该表的**默认排序项**（`SortOption.defaultOf`），而不是「无 ORDER BY」

#### Scenario: 翻页结果稳定性

- **WHEN** 对同一页码与同一筛选条件重复请求 3 次
- **THEN** 三次返回的行序完全一致（无重复行、无漏行）

> **为何要有这条 Scenario**：收口前多个端点在 `orderBy` 为空时走 `<if>` 的 else 分支，
> 即**完全没有 ORDER BY**；MySQL 不保证无 ORDER BY 时的稳定输出，
> 翻页时可能同一行落在两页或某行不出现。**这是本 Change 顺带修掉的既有缺陷。**

---

### Requirement: 凭据字段不得存在于查询对象

`password` / `passwordFuzzy` 等凭据相关字段**不得**作为查询条件存在 ——
存量 MD5 账号的哈希无盐且确定，对其做 `LIKE` 匹配等价于**明文口令猜测预言机**。

#### Scenario: 请求携带 passwordFuzzy

- **WHEN** 调用方对 `/admin/loadUser` 传 `passwordFuzzy=<MD5(猜测)>`
- **THEN** 该字段在 `UserInfoQuery` 中**不存在**，绑定器无从写入
- **AND** `UserInfoMapper.xml` 中不存在任何以 `password` 列为**查询条件**的 `<if>`

#### Scenario: 出参含 PO 的 password 字段

- **WHEN** 管理端用户列表返回 `PaginationResultVO<UserInfo>`（PO 而非 VO）
- **THEN** `password` 因 `UserInfo.password` 上的 `@JsonIgnore` **不参与序列化**
- **AND** 客户端拿不到任何形式的密码哈希

> **本 Scenario 是「已存在的保证」而非新增**：2026-10-06 盘点时曾据
> `base_column_list` 含 `password` 判定「哈希外泄」，读到 `@JsonIgnore` 后**证伪并撤回**。
> 此处固化为规格，防止后续维护者重复误判，也提醒 PO/VO 混用本身仍是待处理的规范偏离。

---

### Requirement: SQL 拼接禁令由机控强制且门禁自身可证

上述各条必须由门禁持续强制，且门禁自身的判别力须经反例检验证明。

#### Scenario: 有人把枚举分支改回 `${}` 拼接

- **WHEN** 任一 Mapper 的 `<choose>` 被改回 `order by ${query.orderBy}`
- **THEN** `verify_sql_concat_guard.mjs` 退出码 1

#### Scenario: 分支与枚举声明漂移

- **WHEN** 某 `<when>` 的 `order by` 片段与枚举项声明的 `sql` 不一致
- **THEN** 门禁报 FAIL，并同时列出「分支实际值」与「枚举声明值」

#### Scenario: 门禁被接入 CI

- **WHEN** `verify_sql_concat_guard.mjs` 被写入 `.github/workflows/ci.yml` 或 `pre-push`
- **THEN** 必须已存在「基线自检 exit 0」与「反例转红」两条实跑记录
- **AND** AGENTS §10 该行不再标注「暂未接入」

#### Scenario: 已登记的不可静态检测项

- **WHEN** 需要判断某个排序项是否真的可从 HTTP 到达
- **THEN** 门禁**一律阻断**，不做「按可达性分级放行」
- **AND** 该盲区按三字段登记：盲区 + 兜底手段 + 兜底手段的实测证据

> **为何「一律阻断」而不分级**：分级需要跨 Controller / Service 的可达性推断，
> 而这正是 AGENTS §2.1 第 12 条点名的高危动作。2026-10-06 恰在该推断上栽了两次 ——
> 一次把「3 个端点可达」说成事实（实际 2 个，端点 1 在 Service 层已被覆盖排序），
> 一次把「PO 出参泄露哈希」说成事实（实际有 `@JsonIgnore`）。

---

## 追溯

- 变更来源：`openspec/archive/2026-10-06-mapper-orderby-sql-injection/`
- 验证证据：`engineering/qa/2026-10-06-sort-whitelist-sql-injection.md`
  （活体注入实测 23/23，含 42 个注入尝试全部 `CODE_1001`）
