# Spec — 数据库迁移自动化

> 2026-10-04 建立。来源：`openspec/archive/2026-10-04-flyway-migration-automation/spec-delta.md`。

## ADDED Requirements

### Requirement: 迁移自动执行与记账

系统在应用启动时按编号顺序自动执行数据库迁移，已执行的跳过，并用持久化的版本历史记录每个库已应用到哪一号。

#### Scenario: 已迁移完成的库重复启动不重跑

- **WHEN** 应用在已迁移完成的库上再次启动
- **THEN** 已执行的迁移被跳过
- **AND** 启动日志显示当前 schema 版本，无重复执行

#### Scenario: 版本历史持久化

- **WHEN** 迁移执行或存量库首次纳管完成
- **THEN** 库中出现迁移版本历史表，记录每个已应用迁移的编号、描述、校验和与执行时间
- **AND** 该表作为**工具产物**不出现在业务基线中，其存在只产生提示级差异、不阻断漂移检查

#### Scenario: 迁移文件命名与位置约定保持不变

- **WHEN** 迁移文件位于仓库根、命名为 `easychat-migration-<NNN>-<描述>.sql`
- **THEN** 系统能直接识别，无需改名
- **AND** 该约定与项目规范（AGENTS §6.4）保持一致
- **AND** 描述中含连字符的文件（如 `004-im-complete`）同样被正确解析

#### Scenario: 已应用迁移被事后改动时启动失败

- **WHEN** 某个已应用迁移的文件内容被修改
- **THEN** 启动失败并明确指出校验和不匹配的文件
- **AND** 该行为为**预期**，不做静默放行

---

### Requirement: 迁移脚本仅使用数据库通用 SQL

迁移脚本不使用 mysql 客户端专有指令，不建存储过程、函数、触发器或事件。

#### Scenario: 脚本不含客户端专有指令

- **WHEN** 任意迁移文件包含 `DELIMITER`
- **THEN** 门禁阻断
- **AND** 说明该指令是客户端专有、迁移工具无法解析，会导致自动执行路径整体失败

#### Scenario: 脚本不建存储过程类对象

- **WHEN** 任意迁移文件包含 `CREATE PROCEDURE` / `CREATE FUNCTION` / `CREATE TRIGGER` / `CREATE EVENT`
- **THEN** 门禁阻断

#### Scenario: 幂等加列的等价写法

- **WHEN** 迁移需要「列不存在才添加」
- **THEN** 使用条件判断 + 动态 SQL 执行（`SET` + `PREPARE` / `EXECUTE` / `DEALLOCATE`）
- **AND** 保持脚本可重复执行
- **AND** 迁移工具能逐条解析执行（已实测）

---

### Requirement: 快照与增量互斥

数据库基线快照与增量迁移脚本是互斥的两条路径，不可先后执行。

#### Scenario: 在已导入快照的库上执行增量失败

- **WHEN** 对一个已导入最新基线快照的库执行增量迁移
- **THEN** 执行失败并报「列名重复」
- **AND** 该失败说明二者互斥，**不得**通过重试或调整顺序绕过

#### Scenario: 全新环境走快照

- **WHEN** 全新环境建库
- **THEN** 导入最新基线快照，并声明当前已是最新的版本号
- **AND** 不执行任何增量迁移

#### Scenario: 存量环境走增量

- **WHEN** 存量环境升级
- **THEN** 只执行增量迁移，**不**重新导入快照
- **AND** 由人声明该库真实版本号后，系统执行其后的迁移

---

### Requirement: 存量库纳管的版本号须经校验

对没有版本历史的存量库，纳管前须验证其结构声明与实际一致。

#### Scenario: 声称最新但结构不符则拒绝启动

- **WHEN** 一个没有版本历史的库声明自己是最新版本，但结构与业务基线不一致
- **THEN** **拒绝启动**并返回非 0 退出码
- **AND** 输出具体差异清单（基线有而活库没有的表 / 列）
- **AND** **不得**基于「假定已全部应用」而继续——假定即事故

#### Scenario: 库为空或已有版本历史

- **WHEN** 库为空，或已有版本历史表
- **THEN** 直接放行，不做结构比对

#### Scenario: 声明了非最新版本号

- **WHEN** 存量库声明的版本号低于仓库内最大迁移号
- **THEN** 视为「合法的待升级存量库」，不做结构比对、不阻断
- **AND** 系统据声明的版本号执行其后的迁移

#### Scenario: 声明的版本号来源

- **WHEN** 纳管版本号由环境变量声明
- **THEN** 校验闸门与应用**读取同一个值**
- **AND** 闸门不以配置文件默认值覆盖环境变量声明（否则合法的升级路径会被堵死）

---

### Requirement: 纳管版本号与迁移集同步

纳管所依据的版本号必须与仓库内迁移集保持一致。

#### Scenario: 新增迁移但未同步纳管版本号

- **WHEN** 仓库内最大迁移号大于配置中的纳管版本号
- **THEN** 门禁**阻断**并报错
- **AND** 提示「新增迁移必须同步更新纳管版本号」
- **AND** 说明后果：存量库会把该新增迁移当作已应用而**静默跳过**

#### Scenario: 迁移编号断裂

- **WHEN** 迁移编号出现缺口，或不再从起始编号开始
- **THEN** 门禁阻断
- **AND** 说明编号断裂会使版本比较与记账失去意义

---

### Requirement: 部署环境自动执行迁移

容器化环境下，迁移在应用启动前完成，且失败会阻断应用启动。

#### Scenario: 编排顺序

- **WHEN** 使用容器编排启动全套服务
- **THEN** 存在一次性的迁移前置校验步骤
- **AND** 应用服务在其**成功完成后**才启动
- **AND** 校验失败时应用**不启动**（fail-closed）

#### Scenario: 闸门与应用使用同一版本号

- **WHEN** 配置了纳管版本号
- **THEN** 前置校验步骤与应用服务使用**同一个**该值
- **AND** 不得出现「校验的是一个值、实际执行的是另一个值」

---

### Requirement: 迁移工具版本须兼容实际数据库版本

迁移工具的版本须支持本项目实际使用的数据库版本，且该约束不被依赖管理覆盖。

#### Scenario: 工具版本与数据库版本不兼容时启动失败

- **WHEN** 迁移工具版本不支持实际数据库版本
- **THEN** 启动失败并报出版本不兼容
- **AND** 该失败必须**显式暴露**，不得降级为跳过迁移

#### Scenario: 版本约束有据可查

- **WHEN** 查阅依赖声明
- **THEN** 迁移工具的版本被显式固定，并注明**为何不能**跟随统一依赖管理
- **AND** 注释指明本项目实际使用的数据库版本

---

### Requirement: 纳管版本号需人工声明一次

存量库首次纳管时，其真实历史版本号由人声明；系统只能往后记账，不能回溯历史。

#### Scenario: 存量库首次纳管

- **WHEN** 一个已跑过若干迁移但无版本历史的库首次接入
- **THEN** 由人声明该库真实跑到的编号
- **AND** 系统据此执行其后的迁移，并从此自动记账
- **AND** 该声明是一次性的，后续无需人工介入

#### Scenario: 声明成本须被明示

- **WHEN** 查阅迁移相关文档
- **THEN** 明确说明「该库跑到第几号」仍需人知道**一次**
- **AND** 提供查询结构差异的手段以便人确定该编号

---

## 门禁

| 脚本 | 阻断条件 |
|------|----------|
| `scripts/verify/verify_migration_flyway.mjs` | 迁移工具版本被删除或改为不支持实际数据库版本 / 迁移文件未打包进产物 / 迁移命名约定配置缺失 / 已应用迁移校验被关闭 / 纳管版本号与仓库最大迁移号不等 / 迁移编号断裂 / 任一迁移含 `DELIMITER` 或存储过程类对象 / 前置校验不存在、缺少拒绝分支、或不以环境变量声明为准 / 编排中应用不依赖前置校验 / 前置校验与应用不共用同一版本号 |
| `scripts/verify/mutation_migration_flyway.cjs` | **反向验证**：14 个变异若有一个未被上述门禁捕获则 exit=1（防「门禁存在但无判别力」） |
| `scripts/verify/verify_schema_drift.mjs` | 基线有的表 / 列活库没有（迁移未执行）、列类型漂移、密码列宽不足、迁移编号断裂 |

> **已知不可静态检测项**：前置校验的**拒绝分支方向**无法从源码文本推出
> （把放行条件反写后，字面量不变、静态断言仍通过）。该情形由**真机三路径验证**兜底
> （无漂移放行 / 有漂移拒绝 / 环境变量声明后放行），不做假覆盖。

---

## Requirement: 迁移脚本可重复执行（migration-idempotency）

仓库根 `easychat-migration-*.sql` 中**所有**结构性 DDL 语句（`ALTER TABLE ... ADD COLUMN` / `ADD [UNIQUE] INDEX` / `DROP INDEX`） SHALL 具备存在性守卫：目标已存在时退化为 `DO 0` 而非报错。任意一份脚本 SHALL 可连续执行两次而两次均成功，且数据库结构 SHALL 不变。

守卫 SHALL 使用 MySQL 5.7 支持的动态 SQL（`SET @var := IF(<存在性探针>, 'DO 0', '<DDL>')` + `PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE`），SHALL NOT 使用 `DELIMITER`、`CREATE PROCEDURE|FUNCTION|TRIGGER|EVENT`，SHALL NOT 使用 MariaDB 专有的 `DROP INDEX IF EXISTS`。

一条 `ALTER TABLE` 内含**多个** `ADD COLUMN` 时 SHALL **拆成逐列独立守卫**（而非以「全部列已存在」为单条守卫）—— 后者在「上次执行中途失败、只加了部分列」时会永久卡死。

一条 `ALTER TABLE` 内同时含 `DROP INDEX` 与 `ADD [UNIQUE] INDEX` 时，守卫条件 SHALL 取**目标对象**（新索引）是否存在，而**非**被 `DROP` 的对象。

#### Scenario: 重复执行不报错

- **WHEN** 对已执行过的库再次执行任一 `easychat-migration-*.sql`
- **THEN** 退出码 SHALL 为 0，SHALL NOT 出现 `ERROR 1060 Duplicate column name` / `ERROR 1061 Duplicate key name` / `ERROR 1091 Can't DROP`
- **AND** 表结构 SHALL 与执行前一致

#### Scenario: 首次执行照常生效

- **WHEN** 对缺少目标列/索引的库首次执行脚本
- **THEN** 守卫条件为假，SHALL 执行与补齐前**逐字相同**的 DDL，最终结构与基线 `easychat.sql` 一致

#### Scenario: 部分已存在时可自愈

- **WHEN** 上次执行中途失败，`user_contact` 已有 `role` 但尚无 `mute_end_time`
- **THEN** 再次执行 SHALL 只补 `mute_end_time`（`role` 退化为 `DO 0`），SHALL NOT 报 1060

#### Scenario: 组合语句按目标态守卫

- **WHEN** 对已存在 `uk_word_flag`、且 `uk_word` 已被删除的库执行 `easychat-migration-007-sensitive-word-admin.sql`
- **THEN** 守卫命中、整条退化为 `DO 0`，SHALL NOT 报 `ERROR 1091 Can't DROP`

---

## Requirement: 迁移脚本幂等性由门禁正向强制（migration-idempotency-gate）

`verify_migration_flyway.mjs` SHALL **正向断言**全部迁移脚本的结构性 DDL 均被存在性探针（`information_schema` / `SHOW COLUMNS` / `SHOW INDEX`）守卫。判定 SHALL **按语句边界**而非固定字符窗口 —— 相邻语句的守卫**不得**替当前语句背书。存在任一无守卫 DDL 时 SHALL 以非 0 退出。

> ⚠ 该断言只能验证「存在性探针的字面量在不在同一条语句里」，**无法验证探针条件写对了**：把 `COLUMN_NAME = 'role'` 改成 `COLUMN_NAME = 'xxx'`、或把 `> 0` 反写成 `< 0`，断言均照样通过。正确性由**真机验证**兜底 —— 每份脚本连跑两次均须 exit 0。

#### Scenario: 无守卫 DDL 被阻断

- **WHEN** 某迁移脚本出现裸 `ALTER TABLE ... ADD COLUMN`（无探针）
- **THEN** 门禁以非 0 退出并指出具体文件与语句

#### Scenario: 探针只在相邻语句中不算数

- **WHEN** 文件中前一条语句有守卫、而当前 `ALTER TABLE` 是裸的
- **THEN** 门禁 SHALL 判为无守卫

#### Scenario: 无引号标识符同样被识别

- **WHEN** DDL 使用不带反引号的标识符（`ADD COLUMN seq`、`ADD INDEX idx_session_seq`）
- **THEN** 门禁 SHALL 识别为结构性 DDL 并纳入判定（识别不到即等于断言空转）
