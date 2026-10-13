# Design — 补齐 HTTP 请求层与主进程 db 层

## 架构

### 阶段 A：HTTP 请求层（**不改生产代码**）

```
request-layer.spec.js
  ├─ vi.mock('axios') → create() 返回可控桩，记录 interceptors.use 注册的 handler
  ├─ 取出 Request.js 在模块加载时注册的两个 handler
  └─ 直接调用 handler(config) / handler(response) / handler(error)
```

**为什么 mock `axios.create` 而不是发真请求**：
拦截器的价值全在「拿到 response/error 之后如何分支」，与网络无关。
直接调 handler 覆盖的是**同一段代码**，且不引入 nock / msw 之类新依赖（零新依赖是本仓一贯取向）。

### 阶段 B：本地 db 层

```
ChatMessageModel.js ─┐
ChatSessionUserModel ─┤        ┌─────────────────────┐
UserSetting.js       ─┼──────▶ │  dbModelCore.mjs    │  纯函数：SQL 文本 + 参数数组
LaterHandleModel.js  ─┤        │  （无 sqlite3）      │  + 字段映射
                      │        └─────────────────────┘
                      ▼
              ADB.js（唯一 require('sqlite3') 的地方，IO 边界）
```

`Tables.js` 零 import，**直接 import 即可测**，无需抽取。

## 决策（ADR）

### ADR-001: 阶段 A 一行生产代码都不改

`Request.js` 是全站 200+ 处调用的枢纽，且**行为本身是正确的**
（2026-10-04 才刚补过 blob 错误体与 4xx 带 body 两条分支）。本次是**补测试**，
不是修缺陷。改动生产代码会引入「测试与实现互相迁就」的风险——
测试存在的意义正是**独立于**实现地验证契约。

### ADR-002: 阶段 B 抽「构造」而不抽「执行」

各 Model 的函数分两类：
- **构造类**：`const sql = "update ... where user_id=? and contact_id=?"; return run(sql, [a, b])`
  —— SQL 文本与参数顺序是纯逻辑，**写错不报错**，是最该测的部分 → 抽出
- **执行类**：`run` / `queryAll` / `update` —— 依赖 sqlite3，抽出也测不了 → 留在 Model

抽出后 Model 退化为 `return run(...buildXxxSql(...))`，可读性不降反升。

### ADR-003: 阶段 B 不改 `Tables.js`

表结构定义零 import、可直接测，且**本次无任何理由改动它**。
抽离它只会制造无谓的 diff 风险。

### ADR-004: 参数数组用「函数返回」而非「模块常量」

SQL 与参数必须**成对构造**（参数顺序跟着 WHERE 条件走）。
若拆成两个独立常量，任一侧改动都可能造成失配而无人发现。
故每个查询导出为一个**返回 `{ sql, params }` 的函数**。

## 风险

| 风险 | 缓解 |
|---|---|
| 阶段 B 抽离时改变 SQL 文本或参数顺序 | 抽离为**逐字搬移**；门禁逐条断言 SQL 文本与参数数组，抽离前后由同一批断言对照 |
| 阶段 A 的 mock 与真实 axios 行为不符 | 拦截器只依赖 `response.config` / `error.config` / `error.response` 三个形状，桩按这三者构造；handler 是真实注册的那一份（非复写） |
| `dbModelCore` 日后与 Model 分叉 | 门禁断言 Model **不再内联 SQL 字符串字面量**（除纯注释外），确保构造逻辑唯一真源在核心 |

## 依赖

**零新依赖**。阶段 A 用 vitest 内置 `vi.mock`；阶段 B 用 node 直接 import 纯 `.mjs`。

## 数据影响

**零**。无表结构变更、无字段变更、无数据迁移。本地 SQLite 内容不受影响。