# Proposal — 收口 Mapper 的 `${}` 字符串拼接，并把排序改为列名枚举白名单

- 创建日期: 2026-10-06
- 效率等级: **L4**（安全红线 §6.2-3 + 持久化查询层 + 管理端权限语义面）
- 关联遗留: `docs/system-facts.md` §14 #23（`${}` 拼接 17 处）、#24（`password` 预言机）

## Why

AGENTS §6.2-3 明令「禁止字符串拼接 SQL，MyBatis 使用 `#{}` 参数绑定」，但这条规范**自始至终零机控**
（`verify_mapper_params.mjs` 只审计 `#{...}` 根名与 `@Param` 的匹配，对 `${}` 一条断言都没有）。
2026-10-06 只读盘点实测：仓库有 **17 处** `order by ${query.orderBy}`，其中 **2 个管理端端点的
`orderBy` 可由请求参数直达**，构成 SQL 注入面；另有 1 个端点（`/admin/loadUser`）可把 `password`
列塞进 WHERE 条件，在存量 MD5 账号（无盐、确定）上构成**明文口令猜测预言机**。

两个问题同源：**`*Query` 对象被 Spring 直接从请求参数绑定，而 Query 的字段没有白名单**。
不修的话，下一次新增管理端列表端点会原样复现。

## What Changes

> ⚠️ 本节在人工确认后已修订：初版写「移除调用方自定义排序能力」，
> 人工决策为**保留该能力、改走列名枚举白名单**（ADR-001）。

- 后端（新增）:
  - **排序白名单载体**（`SortOptionEnum` 或等价形式）：每个可排序列名映射为**固定的 SQL 片段字面量**，
    排序方向独立枚举。**枚举值是代码常量，不是运行时字符串。**
- 后端（Controller）:
  - `AdminGroupController#loadGroup`、`AdminUserInfoBeautyController#loadBeautyAccountList` ——
    请求可传 `sortField` / `sortDirection`，**取值必须命中白名单枚举**，否则回退默认排序。
  - `AdminUserInfoController#loadUser` —— `password` / `passwordFuzzy` **彻底删除**，排序同样走白名单。
  - 其余 9 处列表端点维持现状（它们已在 Controller 或 Service 硬编码排序）。
- 后端（Mapper XML）:
  - 移除 **17 处** `${query.orderBy}`，改为 `<choose>` + `<when>` **枚举分支**，
    每个分支是 XML 内的字面量 `order by <列名> <方向>`（**仍然零 `${}`**）。
  - 删除 `UserInfoMapper.xml` 中 `password` / `passwordFuzzy` 两个 `<if>`（零调用方）。
- 后端（Service）:
  - 两个可达端点在调用方未指定排序时使用**白名单默认项**，顺带修掉既有缺陷：
    **当前 `orderBy` 为空时这两个端点完全没有 ORDER BY**，MySQL 不保证稳定序，
    分页翻页可能重复 / 漏行（见 C4）。
- 后端（Entity/query）:
  - `BaseParam.orderBy` 的 setter 收窄为**包内可见**（外部无法再写任意串）；
    新增 `sortField` / `sortDirection` 且只接受枚举。
- 门禁:
  - `scripts/verify/verify_sql_concat_guard.mjs`：Mapper XML 出现 `${}` 即阻断（含解析器自检）。
    **已实现、已实跑、当前 exit=1 如实报出 17 处**；按 AGENTS §2.1 第 1 条，
    待本 Change 修复完成后再接 CI / pre-push，**两者必须同批**。
- 数据库: **无表结构变更、无迁移脚本**。
- 前端: **零改动**（实测 `easychat-front/src` 全仓无 `orderBy` / `sortField` 字段）。

## Capabilities

- C1: Mapper XML 中**不存在任何 `${}` 字符串拼接**，排序一律为 XML 内字面量分支
- C2: 调用方**仍可指定排序**，但只能取自**列名枚举白名单**，未命中则回退默认
- C3: `password` / `passwordFuzzy` 两个字段**彻底删除**（HTTP 面与类型上均不可达）
- C4: 分页列表**恒有 ORDER BY**（修掉「orderBy 为空则无排序」这一既有缺陷）
- C5: 上述四条由 `verify_sql_concat_guard.mjs` 机控强制，且门禁自身有变异检验证明判别力

## Impact

- 对外接口: **URL、HTTP method、响应结构、权限注解全部不变**；
  `check-api-contract --strict` 保持 0 漂移。
  行为变化：新增 `sortField` / `sortDirection` 两个**可选**入参（不传即默认排序）；
  `orderBy` / `password` / `passwordFuzzy` 变为**无效参数**（被忽略，不报错）。
- 存量数据: **零影响**，无 DDL、无数据迁移。
- 性能: 无影响。
- 安全: 消除「已认证管理员可 SQL 注入」与「MD5 账号口令猜测预言机」两个面。
- 回退方案: 按 commit 可 revert；XML 与 Controller 一一对应，无中间态兼容问题。

---

## ☑ 人工确认关卡（2026-10-06 已确认）

> 确认人：仓库主｜确认方式：逐项书面选择

- [x] **同意方案，允许继续**
- [x] 需修改 → 已按下列决策修订本提案
- [ ] 退回重新评估

### 人工决策记录（三项，均已据此改写四件套）

| # | 决策 | 结论 | 对本提案的影响 |
|---|------|------|----------------|
| 1 | 调用方自定义排序是否保留 | **保留，改走列名枚举白名单** | C2 由「排序不可寻址」改为「排序白名单化」；XML 用 `<choose>` 枚举分支，**仍然零 `${}`** |
| 2 | `password` / `passwordFuzzy` 如何处理 | **彻底删除** | C3 改为「从 HTTP 面与类型上均删除」 |
| 3 | 验收证据强度 | **活体注入实测列为 DoD 硬项** | tasks.md 阶段四 + DoD 均标注「不接受代码级可达代替」 |

### 确认过程中连带查出的一处事实更正

初版提案写「**3 个**管理端端点可达」，逐层核实后**更正为 2 个**：
`/admin/callLog/loadCallLog` 在 `AdminCallLogServiceImpl#loadCallLog` 内已被
`setOrderBy("cl.id desc")` 覆盖，且走的是 `callLogReadMapper`
（`CallLogReadMapper.xml` 排序本就是字面量），**根本没触及 `CallLogMapper`**。
初版只读到 Controller 就下结论 —— 属 AGENTS §2.1 第 12 条点名的「凭局部证据推断系统行为」，
本轮已第二次（第一次是「哈希外泄」，被 `@JsonIgnore` 证伪）。
附带查出：`CallLogMapper.selectList` **全仓零调用** → 其 `${}` 是死代码。
