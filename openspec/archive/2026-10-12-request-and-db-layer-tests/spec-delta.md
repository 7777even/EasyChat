# Spec Delta — 补齐 HTTP 请求层与主进程 db 层

- 关联 Tasks: 2026-10-12-request-and-db-layer-tests/tasks.md
- 创建日期: 2026-10-12

## ADDED Requirements

### Requirement: HTTP 请求层的错误契约（对齐 proposal Capability C1）

`request()` 必须把错误**吞成 `null`** 而非向上抛出，所有调用方依赖该语义做判空。

#### Scenario: 业务错误被吞成 null

- **WHEN** 后端返回 `code != 0`（如 1001）
- **THEN** `request()` 解析出的结果为 **`null`**
- **AND** 弹出 Toast 文案取自 `body.message`
- **AND** **不**抛异常

#### Scenario: 请求发送失败被吞成 null

- **WHEN** 网络层失败（error 拦截器的「请求发送失败」分支）
- **THEN** 结果为 `null`

#### Scenario: showError=false 时不弹 Toast 但仍吞成 null

- **WHEN** 调用时传 `showError: false`
- **THEN** 结果仍为 `null`
- **AND** **不**调用 Message.error

#### Scenario: 成功时返回完整 body

- **WHEN** 后端返回 `code == 0`
- **THEN** `request()` 返回该 body 对象本身（`data` 可用）
- **AND** 不弹任何错误

---

### Requirement: HTTP 响应拦截器的三分支

#### Scenario: 登录过期（2001）走重登录

- **WHEN** 响应 `code == 2001`（**HTTP 200 路径**）
- **THEN** 解析为「登录超时」并 `send('reLogin')`
- **AND** 结果为 `null`

#### Scenario: HTTP 401/403 + body 携带 2001 同样走重登录

- **WHEN** error 路径且 body 为 `{"code":2001,...}`
- **THEN** 解析为「登录超时」并 `send('reLogin')`
- **AND** **不**误报为「网络异常」

#### Scenario: 非 2xx 但 body 是 Result 结构 → 按业务错误处理

- **WHEN** HTTP 403 + body `{"code":2003,"message":"无权限"}`
- **THEN** Toast 文案为 `无权限`
- **AND** **不**显示「网络异常，请检查后端是否启动」

#### Scenario: 无 body 的网络错误才报网络异常

- **WHEN** error 且 `error.response` 为空（连不上）
- **THEN** Toast 文案为「网络异常，请检查后端是否启动」

#### Scenario: blob 错误体先解文本再判业务错误

- **WHEN** `responseType='blob'` 且错误体是 JSON 的 Blob
- **THEN** 从 Blob 读出文本并解析为 `Result`
- **AND** 按业务错误处理（**不**误报网络异常）

#### Scenario: blob 错误体是非 JSON（如网关 HTML 页）

- **WHEN** `responseType='blob'` 且错误体是 HTML
- **THEN** 回退为「网络异常」文案
- **AND** **不**抛解析异常

#### Scenario: errorCallback 被调用且只调用一次

- **WHEN** 调用方传了 `errorCallback` 且发生业务错误
- **THEN** `errorCallback` **恰好被调用一次**，参数为响应 body
- **AND** 结果仍为 `null`

---

### Requirement: 本地 db 层查询构造可被验证（对齐 proposal Capability C2）

各 Model 的「SQL 文本 + 参数数组」必须可被行为测试锁定，
尤其防「参数顺序错误」「条件漏项」这两类**不抛异常**的失效。

#### Scenario: 未读数自增的条件与参数顺序

- **WHEN** 构造 `update chat_session_user set no_read_count = no_read_count+? where user_id=? and contact_id=?`
- **THEN** 参数数组恰为 `[noReadCount, userId, contactId]`
- **AND** **顺序不可颠倒**（顺序错会把别人的会话数改掉，不抛异常）

#### Scenario: 会话列表必须同时带 user_id 与 status

- **WHEN** 构造会话列表查询
- **THEN** WHERE 含 `user_id = ?` **与** `status = 1`
- **AND** 参数数组恰为 `[userId]`

#### Scenario: 消息分页查询的条件与 limit/offset

- **WHEN** 构造历史消息分页查询
- **THEN** 参数含 `userId`、`sessionId`、limit、offset
- **AND** limit/offset 的**先后顺序**正确（颠倒会取错页）

#### Scenario: 更新会话时不改 user_id / contact_id

- **WHEN** 构造会话更新
- **THEN** WHERE 条件为 `{ userId, contactId }`
- **AND** SET 部分**不含** userId / contactId（否则主键被改写）

#### Scenario: 本地服务端口按用户递增分配

- **WHEN** 某用户已有端口 `10241`，再次分配
- **THEN** 返回 `10242`
- **WHEN** 池中无任何记录
- **THEN** 返回 `10240`（起始值）

#### Scenario: 核心保持纯（无 sqlite3）

- **WHEN** 在 node 中直接 import `dbModelCore.mjs`
- **THEN** 不触发建表、不需要 sqlite3
- **AND** 不 `require('sqlite3')` / 不 `process.exit`

#### Scenario: Model 层不得残留内联 SQL 字面量

- **WHEN** 扫描 4 个 Model 的**代码**（剥注释后）
- **THEN** 不含 `select ` / `update ` / `insert ` / `delete ` 等 SQL 关键字开头的字符串
- **AND** 构造逻辑唯一真源在 `dbModelCore.mjs`

---

## MODIFIED Requirements

（无。本 Change 只增加测试与抽离，不改变任何既有契约。）

---

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: HTTP 请求层的错误契约 | 2.1~2.4 |
| C1 | ADDED: HTTP 响应拦截器的三分支 | 2.5~2.8 |
| C2 | ADDED: 本地 db 层查询构造可被验证 | 3.1~3.5, 4.1, 4.2 |