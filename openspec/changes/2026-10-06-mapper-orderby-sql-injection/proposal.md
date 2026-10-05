# Proposal — 收口 Mapper 的 `${}` 字符串拼接与管理端查询对象的请求绑定面

- 创建日期: 2026-10-06
- 效率等级: **L4**（安全红线 §6.2-3 + 持久化查询层 + 管理端权限语义面）
- 关联遗留: `docs/system-facts.md` §14 #23（`${}` 拼接 17 处）、#24（`password` 预言机）

## Why

AGENTS §6.2-3 明令「禁止字符串拼接 SQL，MyBatis 使用 `#{}` 参数绑定」，但这条规范**自始至终零机控**
（`verify_mapper_params.mjs` 只审计 `#{...}` 根名与 `@Param` 的匹配，对 `${}` 一条断言都没有）。
2026-10-06 只读盘点实测：仓库有 **17 处** `order by ${query.orderBy}`，其中 **3 个管理端端点的
`orderBy` 可由请求参数直达**，构成 SQL 注入面；另有 1 个端点（`/admin/loadUser`）可把 `password`
列塞进 WHERE 条件，在存量 MD5 账号（无盐、确定）上构成**明文口令猜测预言机**。

两个问题同源：**`*Query` 对象被 Spring 直接从请求参数绑定，而 Query 的字段没有白名单**。
不修的话，下一次新增管理端列表端点会原样复现。

## What Changes

- 后端（Controller）:
  - `AdminCallLogController#loadCallLog`、`AdminGroupController#loadGroup`、
    `AdminUserInfoBeautyController#loadBeautyAccountList`、`AdminUserInfoController#loadUser`、
    `AdminAppUpdateController#loadUpdateList`、`AdminReportController#loadReport` / `loadAuditLog`、
    `AdminSensitiveWordController#loadWord`、`UserContactController` 两处、`GroupController` 两处
    —— **不再从请求绑定整个 `*Query`**，改为显式声明允许的查询字段（`@ModelAttribute` + 白名单
    setter，或改为接受显式入参 DTO）。
  - `password` / `passwordFuzzy` **不再可从 HTTP 到达**（`UserInfoQuery` 仅内部使用）。
  - 排序**下沉为 Service / Mapper 内硬编码字面量**，排序能力对调用方不可寻址。
- 后端（Mapper XML）:
  - 移除 **17 处** `${query.orderBy}`；`CallLogReadMapper.xml:84` 已有的字面量排序写法作为先例。
  - 同步移除 `UserInfoMapper.xml` 中 `password` / `passwordFuzzy` 两个 `<if>` 查询条件（无任何调用方使用）。
- 后端（Entity/query）:
  - `BaseParam.orderBy` 的 setter 收窄为**包内可见或移除**（ADR 见 design.md）。
- 门禁:
  - 新增 `scripts/verify/verify_sql_concat_guard.mjs`：Mapper XML 出现 `${}` 即阻断；
    请求可绑定的 Query 对象其 `orderBy` 未被端点侧硬编码覆盖即阻断。
    **本门禁已实现且已实跑（exit=1，如实报出 17 处），但按 AGENTS §2.1 第 1 条
    「门禁必须先在当前 main 上跑通才允许接入」，待本 Change 修复完成后再接 CI / pre-push。**
- 数据库: **无表结构变更、无迁移脚本**。
- 前端: **零改动**（实测 `easychat-front/src` 全仓无 `orderBy` 字段，前端从不发送该参数）。

## Capabilities

- C1: Mapper XML 中**不存在任何 `${}` 字符串拼接**，排序一律为 XML 内字面量
- C2: 管理端列表端点的查询字段**白名单化**，调用方无法构造任意 WHERE 条件
- C3: `password` / `passwordFuzzy` 两个字段**从 HTTP 面完全不可达**
- C4: 上述三条由 `verify_sql_concat_guard.mjs` 机控强制，且该门禁自身有变异检验证明其判别力

## Impact

- 对外接口: **URL、HTTP method、请求/响应结构均不变**；`check-api-contract --strict` 保持 0 漂移。
  唯一行为变化：`orderBy` 与 `password*` 变为**不可由调用方指定**（前端本来就不发，无感）。
- 存量数据: **零影响**，无 DDL、无数据迁移。
- 性能: 无影响（去掉调用方可控排序反而少一次字符串拼接）。
- 安全: 消除「已认证管理员可 SQL 注入」与「MD5 账号口令猜测预言机」两个面。
- 回退方案: 单个 commit 可 revert；Mapper XML 与 Controller 改动一一对应，无中间态兼容问题。

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 __________ 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> ### 需人工拍板的三点（我不代替决策）
>
> 1. **排序能力是否真的要保留为调用方可指定？** 我的判断是不保留（前端零使用），
>    改为 Mapper 内字面量。但这会**移除一项现存能力**（调用方自定义排序），
>    若将来有「管理端表格点列头排序」的需求，需另开 Change 走枚举白名单而非字符串。
> 2. **`passwordFuzzy` 是彻底删除，还是保留为内部能力？** 我倾向删除（当前零调用方）。
> 3. **是否要求活体注入实测作为验收的一部分？** 本次已完成**代码级可达性**确证
>    （`${}` 插值 + 请求绑定 + 无 setter 校验，三段都读过），但**未做活库注入实测**
>    （本轮环境无 MySQL）。按 AGENTS §2.1 第 12 条，我不把「代码级可达」说成「已实证」。
