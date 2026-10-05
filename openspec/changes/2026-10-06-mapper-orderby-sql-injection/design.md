# Design — 收口 Mapper 的 `${}` 字符串拼接，并把排序改为列名枚举白名单

- 关联 Proposal: `2026-10-06-mapper-orderby-sql-injection/proposal.md`
- 创建日期: 2026-10-06
- 状态: **人工确认已通过**（2026-10-06）

## 1. 架构设计

问题链路（三段均逐段读过，不是推断）：

```
HTTP 请求参数 ──Spring 绑定──▶ *Query（裸 setter：orderBy / password / passwordFuzzy / 任意字段）
                                    │
                                    ▼
                        Mapper XML  ${query.orderBy}   ← 字符串拼接，不是 #{} 绑定
                                    │
                                    ▼
                               拼出的 SQL
```

```
渲染层 Api.js ──POST /admin/loadUser──▶ UserInfoQuery（整体绑定）
                                          └─▶ UserInfoMapper.xml
                                              <if test="query.passwordFuzzy != null">
                                                and password like concat('%',#{...},'%')
```

### 收口后的形态（排序白名单，零 `${}`）

```
HTTP sortField / sortDirection（可选）
        │
        ▼
Service：白名单校验 ──未命中──▶ 回退默认枚举项
        │ 命中
        ▼
Query.sortOption = 枚举值（不是字符串）
        │
        ▼
Mapper XML：<choose> + <when test="sortOption == 'CREATE_TIME_DESC'">
                        order by create_time desc      ← XML 内字面量
           </when> …
        </choose>
```

**关键点**：白名单校验发生在 **Service**，而 SQL 片段由 **XML 的 `<choose>` 分支**产生 ——
**整条链路上没有任何运行时字符串进入 SQL**，因此 `${}` 归零且门禁可持续强制。
若改用「枚举 → 字符串 → `${}`」，则 `${}` 依然存在，只是把攻击面从「任意串」缩小到「枚举值」——
**不满足 C1，故不采用**。

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 新增（枚举） | 排序白名单：列名 + 方向 → SQL 片段字面量 | 值是**代码常量**；提供 `fromHttp(String)` 做白名单解析 |
| Controller | 2 个可达端点接受 `sortField`/`sortDirection`；`loadUser` 去除 `password*` | 路由 + `@Valid` + 调 Service；不接收任意字符串排序 |
| Service | 白名单校验 + 默认项回退 | **排序决策与合法性判断归 Service**（AGENTS §3.4） |
| Mapper / SQL | 17 处 `${}` → `<choose>` 枚举分支；删 `password*` 两个 `<if>` | **只写 SQL，全部参数 `#{}`，零 `${}`** |
| Entity/query | `BaseParam.orderBy` setter 收窄为包内可见；新增 `sortField`/`sortDirection` | 不承载凭据字段 |

### 前端改动

**零改动**。已实测 `easychat-front/src` 全仓无 `orderBy` / `sortField`（grep 0 命中）。

## 2. 接口设计

**无 URL / method / 出参结构 / 权限注解变更。**

| 端点 | Method | 入参变化 | 出参 | 权限 |
|------|--------|----------|------|------|
| `/api/admin/loadGroup` | POST | 去掉 `orderBy`；新增可选 `sortField` / `sortDirection` | `Result<PaginationResultVO>` | `checkAdmin=true` |
| `/api/admin/loadBeautyAccountList` | POST | 同上 | `Result<PaginationResultVO<UserInfoBeauty>>` | `checkAdmin=true` |
| `/api/admin/loadUser` | POST | 去掉 `orderBy` / `password` / `passwordFuzzy`；新增可选 `sortField` / `sortDirection` | `Result<PaginationResultVO>` | `checkAdmin=true` |

### 错误码

**无新增**。排序参数非法**不报错**，回退默认项（避免为一次排序尝试增加失败路径与错误码分段压力）。

## 3. 数据模型

**无表结构变更、无迁移脚本、`easychat.sql` 不动。**

## 4. 安全设计

- 鉴权: 三个受影响端点均 `checkAdmin = true`
  （已由 `GlobalInterceptorAnnotationContractTest` 反射扫描 118 个受保护端点的断言 B 保证），本次不动鉴权语义。
- 数据权限: 不变。
- 输入校验: 排序参数走**枚举白名单**（`SortOptionEnum.fromHttp`）；非法值静默回退。
- SQL 注入防护: 本变更核心。零 `${}`，并由 `verify_sql_concat_guard.mjs` 机控。

### 附：一个曾被我误判、已证伪并撤回的点

`UserInfoMapper.xml` 的 `base_column_list` 含 `password`，而 `/admin/loadUser` 出参是
`PaginationResultVO<UserInfo>`（**PO 而非 VO**），我据此一度判定「管理端会把哈希吐给客户端」。
读到 `UserInfo.java:50` 的 `@JsonIgnore` 后**证伪并撤回**。

该 PO/VO 混用本身仍是**独立的规范偏离**（§3.6「不复用 Entity 做出参」），
但后果已被 `@JsonIgnore` 挡住，**不在本 Change 范围**，另登记处理。

## 5. ADR

### ADR-001: 保留调用方自定义排序，但改走列名枚举白名单

- 状态: **已接受**（2026-10-06 人工决策）
- 上下文: 初版我提议「排序下沉为 XML 字面量、移除该能力」，理由是前端零使用。
  人工决策为**保留能力**——即承认「管理端表格点列头排序」是合理需求。
- 决策: 请求传 `sortField` + `sortDirection`，Service 用枚举白名单解析，
  Mapper 用 `<choose>` 枚举分支产出 XML 字面量排序。
- 后果:
  - 正面 —— 保留能力；`${}` 仍归零（不用「枚举→字符串→`${}`」的折中）。
  - 负面 —— **多出一个需要维护与测试的契约**（枚举 ↔ XML 分支必须一一对应，
    少一个分支即该排序项静默失效）。故门禁须加断言：
    **枚举项数 == XML `<when>` 分支数**。
- 被拒方案:
  - 「下沉为字面量」—— 人工决策保留能力。
  - 「枚举 → 字符串 → `${}`」—— `${}` 仍在，不满足 C1。

### ADR-002: `password` / `passwordFuzzy` 彻底删除

- 状态: **已接受**（2026-10-06 人工决策）
- 上下文: 全仓**零调用方**（`passwordFuzzy` 仅出现在 `UserInfoMapper.xml`）。
- 决策: 从 `UserInfoQuery` 与 XML 一并删除。
- 后果: 正面 —— 从类型层面消灭「凭据字段混进查询对象」这一类。
  负面 —— 若将来确有「按密码查用户」的管理需求（**本身就不该有**），需重新设计。

### ADR-003: 门禁先实跑报红，修复完成后再接 CI

- 状态: 已接受
- 上下文: AGENTS §2.1 第 1 条 —— 「门禁必须先在当前 main 上跑通，才允许接入」。
  `verify_sql_concat_guard.mjs` 现在跑就是 exit=1（17 处）。
- 决策: 门禁脚本**先落地**（AGENTS §10 标注「当前 exit=1，暂未接入」），
  待本 Change 修复完再接 CI + pre-push，**两者必须同批**。
- 后果: 避免「刚写的门禁立刻把流水线打红」这一 2026-10-02 事故的重演。

### ADR-004: 非法排序值静默回退默认，不报错

- 状态: 待确认（低风险，可与 ADR-001 一并确认）
- 上下文: 报错需要占用错误码分段（§3.1），且排序属非关键体验。
- 决策: 未命中白名单即回退默认项，不抛异常。
- 后果: 正面 —— 零新增失败路径。负面 —— 调用方传错排序**无显式反馈**，
  排障需靠「排序没生效」的现象反推。**若人工认为需要显式反馈，可改抛 `CODE_1001`。**

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| **枚举项与 XML `<when>` 分支数量不匹配** → 某个排序项静默失效 | 中 | 中 | 门禁加断言「枚举项数 == 分支数」+ 单测逐项断言 |
| 白名单遗漏某列 → 该列不可排序 | 中 | 低 | 逐列比对现有 `setOrderBy` 用到的 20+ 个排序值（见 §7 清单），逐一登记 |
| 收口后某管理端页面顺序变化 | 中 | 低 | 默认项取该端点**现状**的排序值；冒烟比对 |
| 门禁判别力不足 | 中 | 高 | `mutation_sql_concat_guard.cjs` + 三字段登记不可静态检测项 |
| 遗漏某处非 `orderBy` 形态的 `${}` | 中 | 高 | 门禁扫**全部** `${}`，不限形态 |
| 前端某处其实依赖了 `orderBy` | 低 | 中 | 已 grep 确证零使用；仍以 `check-api-contract --strict` + 冒烟双验 |

## 7. 依赖与前提

### 现有排序值清单（实施时必须逐一映射到白名单，**不得照抄相邻文件**）

实测全仓 `setOrderBy` 共 20 处调用 + 2 处 Query 覆写：

| 值 | 调用点 |
|---|---|
| `create_time desc` | `AdminUserInfoController:26`、`GroupController:76`、`GroupFileServiceImpl:80`、`MomentNotifyServiceImpl:122/136`、`MomentServiceImpl:145/522`、`UserInfoServiceImpl:453/488` |
| `id desc` | `AdminAppUpdateController:37`、`AppUpdateServiceImpl:163` |
| `cl.id desc` | `AdminCallLogServiceImpl:24`（走 `CallLogReadMapper`，排序已是字面量） |
| `last_apply_time desc` | `UserContactController:104` |
| `last_update_time desc` | `UserContactController:144`、`UserContactServiceImpl:645` |
| `create_time asc` | `GroupController:117`、`MomentServiceImpl:298/316` |
| `last_receive_time desc` | `ChannelContextUtils:115` |
| `role asc, create_time asc` | `GroupInfoServiceImpl:548`（**多列排序**，白名单需支持组合或保留为固定项） |
| `send_time desc` | `ChatMessageServiceImpl:809/876` |
| `message_id desc` / `message_id asc` | `ChatMessageServiceImpl:822/839` |
| `seq ASC` | `HandlerWebSocket:205` |

### 其他前提

- 依赖遗留 **#23 与 #24 同批闭环**（同源，分两批改同一处绑定面更危险）。
- `verify_sql_concat_guard.mjs` 与本 Change **同批接入 CI**，不得分批。
- **需要一次活库注入实测**作为验收证据（代码级可达 ≠ 已实证，AGENTS §2.1 第 12 条）。
