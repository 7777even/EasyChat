# Proposal — Flyway 迁移自动化

- 创建日期: 2026-10-04
- 效率等级: **L4**

## Why

迁移执行链路缺最后一环：**能发现漂移，但没有东西执行迁移，也没有账本记录「这个库跑到第几号了」**。

现状（`docs/system-facts.md` §14 遗留 #9）：

1. `docker-compose.yml` 只在 MySQL **首次启动**导入 `easychat.sql`。存量数据卷永不重放迁移——
   这对「基线 = 最新状态」成立，但一旦发布基线之外的迁移，新环境就漏了。
2. 「某个库应用到第几号」只存在于人脑。README §4.2 让人「按编号顺序执行未跑过的迁移脚本」，
   判断「哪些跑过」完全靠人。
3. `verify_schema_drift.mjs`（2026-10-03 已实现）能**发现**基线与活库的漂移，
   但发现之后仍需人工去翻 12 个 SQL 文件、逐个判断该不该执行。

已造成两次真实事故：BCrypt 列宽截断 500、四张表存量库不存在（`engineering/retro` 有记录）。

**还有一个本轮新发现的阻断项**：`easychat-migration-004-im-complete.sql` 使用
`DELIMITER $$` + `CREATE PROCEDURE` 实现幂等加列，而 **Flyway 的 MySQL 解析器不支持 `DELIMITER`**
（它是 mysql 客户端指令而非 SQL）。这意味着即使不引入 Flyway，**「clone 仓库 → 起 compose →
全新建库 → 顺序执行迁移」这条路今天就是断的**。

## What Changes

- 后端:
  - `pom.xml`：新增 `flyway-core`（**不写版本号**，由 parent `2.6.1` 管理 → 8.0.4；
    该版本 MySQL 支持仍在 core 内，8.2 才拆出 `flyway-mysql`，故**无需额外模块**）。
  - `pom.xml` `<resources>`：把仓库根的 `easychat-migration-*.sql` 复制到 `target/classes/db/migration/`，
    使 dev 直跑与打包运行**同一套** `classpath:db/migration` 路径，且**文件名零改动**
    （保持 AGENTS §6.4-2 规定的 `easychat-migration-<NNN>-*.sql` 命名）。
  - `application.properties` / `-dev` / `-prod`：`spring.flyway.*`（enabled / locations /
    baseline-on-migrate / baseline-version / validate-on-migrate）。
  - **重写 `easychat-migration-004-im-complete.sql`**：存储过程 + `DELIMITER` 换成
    `SET @ddl=(SELECT IF(...))` + `PREPARE`/`EXECUTE`/`DEALLOCATE` 幂等写法。
  - 新增 `scripts/migrate/preflight-baseline-check.mjs`：存量库纳管前置校验。
- 数据库: **无新增表**。Flyway 自建 `flyway_schema_history`（工具产物，不入业务基线，
  故**不改 `easychat.sql`**、不产生 migration-013）。
- 前端: 无改动。

## Capabilities

- **C1**: 迁移按编号顺序自动执行，未执行的才执行，已执行的跳过。
- **C2**: 每个库的应用版本被**持久化记账**（`flyway_schema_history`），`--status` 可只读查看。
- **C3**: 存量库首次纳管前先比对活库与 `easychat.sql`；**一致**才 baseline，**不一致则拒绝**并报错。
- **C4**: 全新库能从零完整执行 001~012（不再被 `DELIMITER` 阻断）。
- **C5**: 迁移文件命名与位置**零改动**，AGENTS §6.4-2 与 README §4.2 的约定保持有效。

## Impact

- **对外接口**: 无。
- **存量数据**: 存量库首次启动会 baseline **不再执行** 001~012（前置比对一致才允许），
  风险极低且 fail-closed；`flyway_schema_history` 为新增工具表。
- **性能 / 安全**: 启动期多一次迁移检查（毫秒级）。`baseline-version` 固定 12 是**硬编码的隐患**，
  故由门禁断言「新增迁移时必须同步 bump」，否则新增的 013 会在存量库被 baseline 跳过 —— **静默漂移**。
- **回退方案**: 关掉 `spring.flyway.enabled` 即回到手工执行；
  `flyway_schema_history` 表可留可删（Flyway 不依赖业务表）。

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 <YYYY-MM-DD> 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估

### 已获人工决策记录（2026-10-04）

| 决策点 | 选定方案 | 否决方案 |
|--------|----------|----------|
| 迁移执行器 | **引入 Flyway**（`flyway-core`，版本随 parent） | 自建轻量 node 执行器 |
| `migration-004` 重写 | 存储过程 → **PREPARE/EXECUTE 幂等写法**（保幂等，AGENTS §6.4-3 不变） | 去掉幂等、依赖版本表 |
| 存量库纳管 | **比对一致才 baseline**（额外前置校验） | 只开 `baseline-on-migrate` 不比对 |
| 接入点 | compose 增 `migrate` service + **新增门禁守护** | `--status` 只读模式（本批不做，见遗留） |