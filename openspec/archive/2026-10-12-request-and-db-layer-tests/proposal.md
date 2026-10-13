# Proposal — 补齐剩余两层零覆盖：HTTP 请求层 与 主进程本地 db 层

- 创建日期: 2026-10-12
- 效率等级: L3

## Why

`docs/system-facts.md` §14 遗留 #7 记录的前端覆盖缺口还剩两处（`Chat.vue` 按成文决策不挂载，不在此列）。
这两处都是**「静默失效」高发区**，却各自零自动化覆盖：

### 阶段 A — HTTP 请求层 `Request.js`（208 行，零覆盖）

三处契约无人验证：
1. `request()` 把错误**吞成 `null`**（所有调用方都必须判空）——语义若被改成 throw，全站调用点会集体炸；
2. 响应拦截器的三分支：`code == 0` 成功 / `code == 2001` 跳登录 / 其他错误；
3. 错误拦截器的 `rejectByBody`：**HTTP 非 2xx 但 body 是 `Result` 结构**时必须按业务错误处理
   （不得误报「网络异常」），且 blob / arraybuffer 的错误体是**二进制**需先解出文本。

其中第 3 条是 2026-10-04 才补的（此前真实错误码被误报为网络异常），**至今无任何测试守护**。

### 阶段 B — 主进程本地 db 层（4 个 Model 共 555 行，零覆盖）

`ADB.js` 在**模块加载时** `require('sqlite3')` 并调用 `init()` 建表，
故 4 个 Model 在 node/vitest 中 import 即建表，完全不可测。
而这一层恰好是「静默失效」的典型：
列名写错、where 条件漏项、参数顺序颠倒，**都不抛异常**，只表现为
「数据没写进去」或「写到了别人的行」。

已覆盖的部分：纯 SQL 构造核心 `dbSqlCore.mjs`（`verify_local_db_core.mjs`，12 项断言）。
**未覆盖**：各 Model 的「查什么条件 / 传什么参数 / 字段怎么映射」。
`Tables.js`（86 行表结构定义）零 import，可直接测，同样未覆盖。

## What Changes

- 后端: 不涉及。
- 前端:
  - **阶段 A**：新增 `__tests__/request-layer.spec.js`，mock `axios.create` 捕获拦截器
    handler 后**直接调用**，逐条验证上述三处契约。不改 `Request.js` 生产代码。
  - **阶段 B**：新增 `src/main/db/dbModelCore.mjs` —— 把 4 个 Model 中
    「SQL 文本 + 参数数组 + 字段映射」的构造逻辑抽为纯函数
    （`src/main/db/*` 下，无 sqlite3 依赖）；
    新增门禁 `scripts/verify/verify_db_model_core.mjs`；4 个 Model 改为调用该核心，
    **IO 调用顺序与语义不变**。
- 数据库: **无变更**（本地 SQLite 的表结构本身不动，只改构造逻辑的存放位置）。

## Capabilities

- C1: HTTP 请求层的**错误吞成 null**、**成功/登录过期/业务错误三分支**、
     **非 2xx 带 Result body 按业务错误处理**、**blob 错误体解文本** 四条契约可被行为测试锁定
- C2: 本地 db 层各 Model 的「查询条件与参数构造」可被行为测试锁定，
     含「参数顺序错误」「条件漏项」「where 真值过滤」三类静默失效形态

## Impact

- 对外接口: 无（前端内部 + 本地缓存）。
- 存量数据: 无影响（表结构与数据均不变；`Tables.js` 内容不改）。
- 性能 / 安全: 无。
- 回退方案: 阶段 A 可整体删除 spec（不改生产代码）；
  阶段 B 可把 `dbModelCore.mjs` 的函数搬回各 Model 并删除该文件。

---

## ☑ 人工确认关卡

> 本提案经 **用户（需求方）** 于 **2026-10-12** 确认，允许进入 design 阶段。
> 原始选项：「C. 两项全补」（在同一问题内并列两阶段）。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

> **立项前已查实的规模与障碍**（避免选项建立在假设上）：
> `db/` 共 7 文件 1031 行；`Tables.js` 86 行**零 import** 可直接测；
> `ADB.js` 224 行是唯一的 sqlite3 加载点（4 个 Model 均经它）；
> `Request.js` 208 行无环境障碍，`axios.create` 可 mock 后直接调用拦截器 handler。