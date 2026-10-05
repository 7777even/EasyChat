# Design — 收口 Mapper 的 `${}` 拼接与管理端查询对象的请求绑定面

- 关联 Proposal: `2026-10-06-mapper-orderby-sql-injection/proposal.md`
- 创建日期: 2026-10-06

## 1. 架构设计

问题链路（三段均已逐段读过，不是推断）：

```
HTTP 请求参数  ──Spring 参数绑定──▶  *Query 对象（裸 setter，含 orderBy / password / passwordFuzzy）
                                        │
                                        ▼
                              Mapper XML  ${query.orderBy}   ← 字符串拼接，不是 #{} 绑定
                                        │
                                        ▼
                                   拼出的 SQL
```

```
渲染层 Api.js ──POST /admin/loadUser──▶ UserInfoQuery（整体绑定）
                                          └─▶ UserInfoMapper.xml 的
                                              <if test="query.passwordFuzzy != null">
                                                and password like concat('%',#{...},'%')
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | 11 处列表端点不再整体绑定 `*Query`；`orderBy` / `password*` 不再从 HTTP 可达 | 路由 + `@Valid` + 调 Service；**不接收调用方可寻址的排序/凭据字段** |
| Service | 排序策略下沉到 Service 常量或 Mapper 内字面量 | 业务规则 + 事务边界；**排序决策归 Service** |
| Mapper / SQL | 17 处 `${query.orderBy}` → 删除；`UserInfoMapper.xml` 删 `password` / `passwordFuzzy` 两个 `<if>` | **只写 SQL，全部参数用 `#{}`**（AGENTS §6.2-3） |
| Entity/query | `BaseParam.orderBy` setter 收窄；`UserInfoQuery.password` / `passwordFuzzy` 删除或收窄 | 与表结构无关的查询参数对象；**不承载凭据字段** |

### 前端改动

**零改动**。已实测 `easychat-front/src` 全仓无 `orderBy` 字段（grep 0 命中），
即前端从不发送该参数，收口对 UI 无感。

## 2. 接口设计

**无对外接口变更**（URL / method / 出参结构 / 权限注解全部不变）。

| 端点 | Method | 入参变化 | 出参 | 权限 |
|------|--------|----------|------|------|
| `/api/admin/callLog/loadCallLog` | POST | 去掉 `orderBy`（其余不变） | `Result<PaginationResultVO<AdminCallLogVO>>` | `checkAdmin=true` |
| `/api/admin/loadGroup` | POST | 去掉 `orderBy` | `Result<PaginationResultVO>` | `checkAdmin=true` |
| `/api/admin/loadBeautyAccountList` | POST | 去掉 `orderBy` | `Result<PaginationResultVO<UserInfoBeauty>>` | `checkAdmin=true` |
| `/api/admin/loadUser` | POST | 去掉 `orderBy` / `password` / `passwordFuzzy` | `Result<PaginationResultVO>` | `checkAdmin=true` |

### 错误码

**无新增**。本变更不产生新的失败路径（排序参数被忽略而非报错）。

## 3. 数据模型

**无表结构变更、无迁移脚本、`easychat.sql` 不动。**

## 4. 安全设计

- 鉴权: 三个受影响端点均为 `checkAdmin = true`（已由 `GlobalInterceptorAnnotationContractTest`
  反射扫描全仓 118 个受保护端点的断言 B 保证），**本次不改动鉴权语义**。
- 数据权限: 不变。
- 输入校验: 新增**字段白名单** —— Query 的 setter 收窄后，Spring 无法绑定调用方未声明的字段。
- SQL 注入防护: 本变更的核心。移除全部 `${}`，并由 `verify_sql_concat_guard.mjs` 机控。

### 附：一个曾被我误判、已证伪并撤回的点

`UserInfoMapper.xml` 的 `base_column_list` 含 `password`，而 `/admin/loadUser` 的出参类型是
`PaginationResultVO<UserInfo>`（**PO 而非 VO**），我据此一度判定「管理端会把密码哈希吐给客户端」。
读到 `UserInfo.java:50` 的 `@JsonIgnore` 后**证伪并撤回** —— 哈希不会被序列化。

该 PO/VO 混用本身仍是个**独立的规范偏离**（AGENTS §3.6「不复用 Entity 做出参」），
但其后果已被 `@JsonIgnore` 挡住，**不在本 Change 范围内**，另登记处理。

## 5. ADR

### ADR-001: 排序下沉为 Mapper 内字面量，不保留「调用方自定义排序」

- 状态: 待确认
- 上下文: 前端零使用 `orderBy`；保留它需要「字符串 → 合法列名」的枚举白名单，
  而白名单本身又是一个需要维护与测试的契约。
- 决策: 删除 `${query.orderBy}` 与 `<if>`，排序写死为 XML 内字面量。
- 后果: 正面 —— 17 处拼接归零、门禁可持续强制。
  负面 —— **移除一项现存能力**；将来若需「点列头排序」须另开 Change 走枚举白名单。

### ADR-002: `password` / `passwordFuzzy` 直接删除，而非仅从 HTTP 隐藏

- 状态: 待确认
- 上下文: 全仓**零调用方**（已 grep 确证 `passwordFuzzy` 仅出现在 `UserInfoMapper.xml`）。
- 决策: 从 `UserInfoQuery` 与 XML 一并删除。
- 后果: 正面 —— 从类型层面消灭「凭据字段混进查询对象」这一类。
  负面 —— 若将来确有「按密码查用户」的管理需求（**本身就不该有**），需重新设计。

### ADR-003: 门禁先实跑报红，修复完成后再接 CI

- 状态: 已接受
- 上下文: AGENTS §2.1 第 1 条 —— 「门禁必须先在当前 main 上跑通，才允许接入」。
  `verify_sql_concat_guard.mjs` 现在跑就是 exit=1（17 处）。
- 决策: 门禁脚本**先落地**（附在 AGENTS §10 标注「暂未接入」），待本 Change 修复完再接
  CI + pre-push，两者**必须同批**。
- 后果: 避免「刚写的门禁立刻把流水线打红」这一 2026-10-02 事故的重演。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 收口排序后某个管理端页面顺序变化 | 中 | 低（视觉） | 排序值原样搬进 XML 字面量，逐个比对；冒烟截图或行为证据 |
| 遗漏某处 `${}`（例如非 `orderBy` 形态的 `${}`） | 中 | 高 | 门禁扫**全部** `${}`，不只 `orderBy`；配变异检验 |
| 门禁自身判别力不足（只认字面量、认错方向） | 中 | 高 | 配 `mutation_sql_concat_guard.cjs`；按 §2.1 第 3 条登记不可静态检测项 |
| 收口后前端某处依赖了 `orderBy` | 低 | 中 | 已 grep 确证零使用；仍需 `check-api-contract --strict` + 冒烟双验 |

## 7. 依赖与前提

- 依赖遗留 #23 与 #24 同批闭环（两者同源，分两批改同一处绑定面反而更危险）。
- `verify_sql_concat_guard.mjs` 与本 Change **同批接入 CI**，不得分批。
- 需要一次**活库注入实测**作为验收证据（代码级可达 ≠ 已实证）。
