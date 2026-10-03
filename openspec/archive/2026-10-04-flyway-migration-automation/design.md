# Design — Flyway 迁移自动化

- 关联 Proposal: `openspec/changes/2026-10-04-flyway-migration-automation/proposal.md`
- 创建日期: 2026-10-04

## 1. 架构设计

```
                    ┌─ 全新库 ────────────────────────────────┐
docker compose up  │  mysql 首启导入 easychat.sql（最新基线）    │  ⚠ 基线已含所有表结构
        │          │  → Flyway 检测到无历史表 + 非空 schema      │     故迁移 001~012 对全新库
        │          │  → 不会 baseline，而是**从头真实执行**        │     是「空操作」（幂等）
        │          └────────────────────┬───────────────────────┘
        │                               │
        │          ┌─ 存量库（手工跑过 001~012，无历史表）────┐
        │          │  Flyway baseline-on-migrate 会跳过 001~012  │
        │          │  ⚠ 但它**不做 schema 比对** → 由前置脚本把关  │
        │          └────────────────────┬───────────────────────┘
        ▼                               ▼
  migrate service（一次性）   ──►  preflight-baseline-check.mjs
   （跑同一 backend 镜像，                    │
    CMD 覆盖为 flyway 相关）                  ├─ 无 flyway_schema_history 且库非空
                                             │    → 比对活库 vs easychat.sql
                                             │      · 0 漂移 → 放行（随后由 Flyway baseline）
                                             │      · 有漂移 → 退出码 1，拒绝启动，报错说明
                                             └─ 其它情况 → 放行
        │
        ▼
  backend（depends_on: migrate: service_completed_successfully）
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 构建 | `pom.xml` 新增 `flyway-core` + `<resources>` 复制迁移文件 | 迁移文件进 jar 的 `db/migration` |
| 配置 | 三段 properties 加 `spring.flyway.*` | 环境无关项进基线，环境相关项进 profile |
| Service | 无 | Flyway 在启动期由 Spring Boot 自动装配 |
| SQL | **重写 `migration-004`** | 幂等加列，不改行为 |

### 前端改动

无。

## 2. 接口设计

无对外接口变更。新增一个**运维命令**（非 HTTP）：

```
node scripts/migrate/preflight-baseline-check.mjs
  退出码：0 放行 / 1 拒绝（并打印差异清单）
```

## 3. 数据模型

**无新增业务表。** `flyway_schema_history` 由 Flyway 自建。

> **刻意不入 `easychat.sql` 基线**：它是工具产物而非业务表。若入基线，
> ① 每个全新库都要手工带一张空历史表；② 它由 Flyway 自己维护版本，与基线并存易出现「基线有、历史无」的矛盾。
> 故它只存在于运行库，由 `verify_schema_drift.mjs` 的「活库多出的表 → WARN」容忍。

## 4. 安全设计

- **fail-closed 三处**：
  ① 存量库无历史表且结构与基线不一致 → 前置脚本拒绝，backend 不启动；
  ② `validate-on-migrate=true`（默认）→ 已应用迁移的文件被改动过 → 启动失败；
  ③ 迁移执行中任一语句失败 → Flyway 回滚该迁移事务并中止启动。
- **不新增凭据**：连接参数复用既有 `spring.datasource.*`。
- **无 SQL 注入面**：迁移文件是仓库内受信资产，不接受外部输入拼接。

## 5. ADR

### ADR-001: 用 Flyway 的 `sql-migration-prefix` + `separator` 适配既有文件名，而不是改名

- 状态: 已接受
- 上下文: Flyway 默认只认 `V1__desc.sql`。仓库现有 12 份文件名为
  `easychat-migration-001-group-management.sql`，且 **AGENTS §6.4-2 明文规定**了这个命名。
- 决策: 配 `spring.flyway.sql-migration-prefix=easychat-migration-` 与
  `sql-migration-separator=-`，使既有文件名**原样**可被识别；文件仍留在仓库根。
- 后果: 正面——**零改名**，AGENTS §6.4-2、README §4.2、既有提交历史全部不受影响。
  负面——该组合比 Flyway 默认约定不常见，需在 `application.properties` 旁写明；
  且描述中含 `-`（如 `im-complete`）时依赖「按第一个分隔符切分」这一 Flyway 行为，**须由测试验证**。

### ADR-002: 迁移文件通过 Maven `<resources>` 复制进 jar，而不是运行时读文件系统路径

- 状态: 已接受
- 上下文: 迁移文件在**仓库根**，而 jar 内没有；若配 `filesystem:` 路径，
  运行期 CWD 因部署方式而异（`java -jar` / IDE / Docker 各不同），极易「开发能跑、容器找不到脚本」。
- 决策: `<resource><directory>${project.basedir}/..</directory><include>easychat-migration-*.sql</include><targetPath>db/migration</targetPath></resource>`，
  再配 `spring.flyway.locations=classpath:db/migration`。
- 后果: 正面——dev 直跑与打包运行**同一路径**，无 CWD 依赖。
  负面——迁移文件进了 jar，Docker 镜像含它们（体积可忽略，且这正是我们要的）。

### ADR-003: 存量库纳管的 schema 比对做在**前置脚本**，而非依赖 Flyway

- 状态: 已接受
- 上下文: `baseline-on-migrate=true` 只看「schema 非空且无历史表」，**不比对内容**。
  若某存量库实际只跑到 011，Flyway 会把它 baseline 到 012 并**跳过 012** → 静默漂移。
- 决策: 前置脚本先比对活库与 `easychat.sql`（复用 `verify_schema_drift.mjs` 的解析思路），
  0 漂移才放行；否则拒绝。
- 后果: 正面——把「baseline 假定」换成「baseline 经验证」，与本项目「不靠假设」的一贯取向一致。
  负面——多一个必须先于 backend 启动的步骤（compose 里用 `service_completed_successfully` 表达）。

### ADR-004: `baseline-version` 固定 12，由门禁守护

- 状态: 已接受
- 上下文: `baseline-version=12` 意味着「存量库视为已应用 001~012」。
  若将来新增 `migration-013` 却忘记 bump，新增的 013 在存量库会被**静默跳过**。
- 决策: 门禁 `verify_migration_flyway.mjs` 断言
  `spring.flyway.baseline-version` **等于**仓库内最大迁移编号。
- 后果: 正面——把「靠人记得」变成机控阻断。
  负面：新增迁移时门禁会红，需要**刻意**去 bump——这是有意的摩擦。

### ADR-005: `migration-004` 保留幂等（改用 PREPARE/EXECUTE），不改成「靠版本表保证只跑一次」

- 状态: 已接受
- 上下文: 去掉幂等能让 SQL 变简单，但 AGENTS §6.4-3 与 README §4.2 都明文要求
  「迁移脚本必须幂等，可重复执行」——因为运维至今仍可能手工重跑。
- 决策: 用 `SET @ddl=(SELECT IF(列存在,'SELECT 1','ALTER ...'))` + `PREPARE`/`EXECUTE`/`DEALLOCATE`。
- 后果: 正面——语义不变，AGENTS 与 README 不用改。
  负面：SQL 可读性下降；**且必须实测 Flyway 能解析 `PREPARE`/`EXECUTE`**
  （若不支持则本 ADR 需推翻，回退到 ADR 备选：拆成独立小脚本）。

---

## 0. 实测结论对设计的修正（2026-10-04，先测后改）

> 本节记录**三个被实测推翻的假设**。它们直接改写了上文若干条，
> 保留在此而非删除，是为了不让人重复走一遍同样的路。

### 实测 1：Flyway Community 8.0.4 **不支持 MySQL 5.7** → 版本必须钉 7.15.0

parent `spring-boot-starter-parent:2.6.1` 管理的是 `flyway-core:8.0.4`，而
**Flyway Community 自 8.0 起不再支持 MySQL 5.7**（5.7 仅保留在付费 Teams 版）：

```
FlywayEditionUpgradeRequiredException: Flyway Teams Edition or MySQL upgrade required:
MySQL 5.7 is no longer supported by Flyway Community Edition
```

本项目**本机开发库就是 MySQL 5.7**（`docker-compose.yml` 用 8.0，两边本就不一致），
随 parent 走 8.0.4 会让开发者本地**根本起不来**。

**修正**：`pom.xml` 显式钉 `flyway-core:7.15.0`（最后一个 Community 下同时支持 5.7 与 8.0 的版本）。
这是本仓「依赖版本一律由 parent 管理」取向的**有实测依据的例外**，已在 pom 注释中写明理由与证据文件。

**已验证**：7.15.0 在 MySQL 5.7.39 上 `Successfully validated 12 migrations` 并正常进入 migrate 流程。

### 实测 2：`sql-migration-prefix` + `separator=-` **确实可用**（ADR-001 成立）

`Successfully validated 12 migrations` —— 12 份文件全部被识别，含描述中带 `-` 的
`004-im-complete` 与 `010-password-and-im-tables`。**零改名**保住了 AGENTS §6.4-2 的命名约定。

### 实测 3：**快照与增量互斥** —— 本设计的核心模型修正

以 `baseline-version=0` 强制从 001 执行到 012（在已导入 `easychat.sql` 的库上）：

```
Migrating schema `ec_flyway_probe` to version "001 - group-management"
Migration ... failed!  Error Code : 1060   ← Duplicate column name
```

**根因**：`easychat.sql` 是**最新快照**，`easychat-migration-*.sql` 是**增量**，
两者是**互斥的两条路**而非先后关系。这正是 compose 只在首启导快照的原因——那个做法本身是对的。

进一步核查发现：**12 份迁移里有 6 份明确不是幂等的**
（001 / 002 / 006 / 007 / 009 / 011 含 `ADD COLUMN` 但无存在性守卫），
其中 `001` 注释自述「MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，此处按首次迁移处理」——
**与 AGENTS §6.4-3「迁移脚本必须幂等」相矛盾**，该矛盾长期存在（见遗留表新增项）。

**模型修正（本次最终采纳）**：Flyway **只负责「从此往后记账」**，不再幻想自动回溯存量库版本：

| 环境 | 做法 |
|------|------|
| **全新环境** | 导入 `easychat.sql` 快照 + `baseline-version=12`（已是最新，**不跑任何迁移**） |
| **存量环境** | 运维**一次性声明该库真实版本号**（如 `SPRING_FLYWAY_BASELINE_VERSION=9`），Flyway 随即执行 010~012 并记账 |
| **此后** | 全部自动：启动即检测并执行未应用迁移 |

**代价（须明确告知）**：「这个存量库跑到第几号」仍需人**一次性**知道——
Flyway 能替人**往后记账**，但无法替人**回溯**历史。这是标准 Flyway 采用路径的成本。

**前置校验的职责相应收窄**：不再试图判断存量库真实版本，只做一件事——
**「声称自己是最新版，但结构与基线不一致」时拒绝启动**（fail-closed，防「假最新」）。

### 实测 4：空库上应用无法自举（修正 C4）

空库启动时失败于 `SensitiveWordServiceImpl` 的 `@PostConstruct` 直接查 `sensitive_word` 表
（表不存在），**并非** Flyway 的问题。说明**基线导入是应用自举的必经步骤**，
「靠迁移从空库建起全部表」这条路需要先改 `SensitiveWordServiceImpl` 等多处
`@PostConstruct` 的容错，属独立变更。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|----------|
| **Flyway 8.0.4 不支持 `PREPARE`/`EXECUTE`** | 中 | 高（全新库起不来） | **实施第一步就是拿真库实测**（ADR-005）；不支持则回退方案：把 004 的加列部分拆出为不使用动态 SQL 的等价写法 |
| `sql-migration-prefix` + `separator=-` 组合解析不出预期版本 | 中 | 高（迁移全不认） | 实施时用 `--status`/启动日志核对「识别到 12 个迁移」，并写进门禁 |
| 新增 013 忘记 bump `baseline-version` | 中 | 高（静默跳过） | ADR-004 门禁阻断 |
| 已应用迁移被事后编辑 → 校验和冲突 | 低 | 中（启动失败） | 这正是期望行为（`validate-on-migrate` 默认开）；失败信息明确指出文件 |
| 前置比对因「活库多出业务表」误判为漂移 | 低 | 中（拒绝启动） | 比对只判「基线有而活库没有」；反向差异仅 WARN，与 `verify_schema_drift.mjs` 口径一致 |
| CI 跑 `mvn package` 时资源复制失败 | 低 | 中 | 打包后断言 `target/classes/db/migration/easychat-migration-001-*.sql` 存在 |

## 7. 依赖与前提

- **新增生产依赖**: `org.flywaydb:flyway-core`（版本由 parent `spring-boot-starter-parent:2.6.1` 管理 → **8.0.4**，**不手写版本号**）。Flyway 8.0.4 的 MySQL 支持在 core 内（8.2 才拆 `flyway-mysql`），**无需额外模块**。
- **前置**: 依赖 `2026-10-03` 的 `verify_schema_drift.mjs`（本变更的比对思路与之同源）。
- **后续（本批不做）**：
  - `--status` 只读模式（输出「已应用 / 待应用」清单，供发版前人工核对）；
  - CI 中对**新库**跑一次「从零执行 001~012」的冒烟；
  - 把迁移文件迁入 `src/main/resources/db/migration/`（与 ADR-001/002 冲突，需先改 AGENTS §6.4-2 命名，故不做）。