# Spec：local-sqlite-cache（本地 SQLite 缓存一致性）

> 真源说明：本地 SQLite 是服务端数据的**缓存**，服务端为真源。本 capability 约束「缓存表结构与写入路径的一致性与安全性」。

## Requirement: 本地库启动自动补列

本地 SQLite 表结构缺失字段时，应用启动阶段必须以幂等方式补齐，不得静默丢弃写入。

#### Scenario: 存量库缺列

- **WHEN** 应用启动且 `chat_session_user` 不存在 `no_disturb` 或 `draft` 列
- **THEN** `Tables.js#alter_tables` 对缺失列逐条执行 `alter table ... add column`
- **AND** 已存在该列时跳过（重复启动不报错）

#### Scenario: 新建库

- **WHEN** 用户首次运行、本地库新建
- **THEN** `add_tables` DDL 直接包含 `no_disturb integer default 0` 与 `draft varchar default null`

---

## Requirement: 草稿本地持久化与恢复

会话草稿必须落本地 SQLite 并在下次加载会话列表时可恢复。

#### Scenario: 写入并恢复

- **WHEN** 渲染层经 IPC `saveSessionDraft` 保存草稿，或服务端 `-7` 同步帧下发 `draft`
- **THEN** `chat_session_user.draft` 更新为该值
- **AND** 下次 `loadSessionData` 读回的会话对象含 `draft`，`MessageSend.vue` 据此回填输入框

---

## Requirement: 免打扰本地持久化

免打扰状态必须落本地 SQLite，供通知抑制判定与菜单状态展示读取。

#### Scenario: 写入并读回

- **WHEN** 渲染层经 IPC `setSessionNoDisturb` 或 `-7` 同步帧设置 `noDisturb=1`
- **THEN** `chat_session_user.no_disturb` 为 `1`
- **AND** `Chat.vue` 通知抑制分支（`session.noDisturb == 1`）与「消息免打扰」菜单读到该值

---

## Requirement: 空值写入安全

会话属性写入在列缺失或值为空时不得产生非法 SQL。

#### Scenario: 同步帧不带值

- **WHEN** `-7` 同步帧的 `value` 为 `null`/`undefined`
- **THEN** `updateSessionAttr` 直接跳过，不落库

#### Scenario: 空 set / 空 where

- **WHEN** `ADB#update` 拼不出任何可更新列或任何 where 条件
- **THEN** 短路返回 `0`（与 `run()` 受影响行数口径一致），不执行 SQL、不弹错误框

---

## Requirement: 本地 db 层查询构造可被验证

`ADB.js` 在**模块加载时** `require('sqlite3')` 并 `init()` 建表，故各 Model 在 node 中
import 即建库、**完全不可测**。而这一层恰是「静默失效」高发区：列名写错、where 条件漏项、
参数顺序颠倒**都不抛异常**，只表现为「数据没写进去」或「写到了别人的行」。

故各 Model 的「SQL 文本 + 参数数组」构造必须抽到**纯核心** `src/main/db/dbModelCore.mjs`
（无 sqlite3 / 无 store / 无 IO），Model 退回纯 IO 接线。

#### Scenario: 未读数自增的条件与参数顺序

- **WHEN** 构造 `update chat_session_user set no_read_count = no_read_count+? where user_id=? and contact_id=?`
- **THEN** 参数数组恰为 `[noReadCount, userId, contactId]`
- **AND** **顺序不可颠倒**（顺序错会把别人的会话数改掉，不抛异常）

#### Scenario: 会话列表必须同时带 user_id 与 status

- **WHEN** 构造会话列表查询
- **THEN** WHERE 含 `user_id = ?` **与** `status = 1`
- **AND** 参数数组恰为 `[userId]`

#### Scenario: 消息分页查询的 limit/offset 顺序

- **WHEN** 构造搜索分页查询（SQL 尾为 `limit ?,?`）
- **THEN** 参数数组末尾恰为 `[offset, limit]`
- **AND** count 与分页两条语句的**条件子句完全一致**（原实现曾把条件构造复制两份，
  改一处漏一处会让「页码总数与实际条数」不符且不抛异常，已由 `buildSearchConditions` 消除重复）

#### Scenario: 更新会话时不改 user_id / contact_id

- **WHEN** 构造会话更新
- **THEN** WHERE 条件为 `{ userId, contactId }`
- **AND** SET 部分**不含** userId / contactId（否则主键被改写）

#### Scenario: 动态 SET 子句的列名映射与 0 值

- **WHEN** 构造会话属性同步的 SET 子句
- **THEN** 驼峰字段名映射为 snake_case **列名**（直接拼驼峰会产出不存在的列）
- **AND** `noReadCount = 0` **仍生成子句**（判据是 `!== undefined` 而非真值，
  否则同步帧「把未读清零」会静默不生效）

#### Scenario: 本地服务端口按全表最大值递增

- **WHEN** 某用户已有端口 `10241`，再次分配
- **THEN** 返回 `10242`
- **WHEN** 池中无任何记录（空表）
- **THEN** 返回 `10240`（起始值）
- **AND** 取的是**全表** max（不加 `where user_id`），故各用户端口互不相同，
  避免同机两用户撞端口导致 `EADDRINUSE`

#### Scenario: 核心保持纯

- **WHEN** 在 node 中直接 import `dbModelCore.mjs`
- **THEN** 不触发建表、不需要 sqlite3
- **AND** 不 `require('sqlite3')` / 不 `process.exit`

#### Scenario: Model 层不得残留内联 SQL 字面量

- **WHEN** 扫描 4 个 Model 的**代码**（剥注释后）
- **THEN** 不含 `select`/`update`/`insert`/`delete` 开头的 SQL 字符串字面量
- **AND** 构造逻辑唯一真源在 `dbModelCore.mjs`
