# Tasks — Flyway 迁移自动化

- 关联 Design: `openspec/changes/2026-10-04-flyway-migration-automation/design.md`
- 创建日期: 2026-10-04
- 预估总工时: 7h

## 阶段零：**先实测再动手**（本变更的关键前置）

> ADR-005 的成败取决于 Flyway 8.0.4 能否解析 `PREPARE`/`EXECUTE`。
> **不实测就写实现，等于把最可能翻车的点押到最后。**

- [x] **T0.1** `pom.xml` 加 `flyway-core` + `<resources>` 复制迁移文件到 `db/migration`；打包后断言存在 — ≤30min
- [x] **T0.2** 三段 properties 写 `spring.flyway.*` — ≤30min
- [x] **T0.3** **实测**：留档 DELIMITER 与版本墙的失败证据 — ≤1h
- [x] **T0.4** `migration-004` 改写为 PREPARE/EXECUTE；在隔离夹具上让 Flyway 真实执行 + 验幂等 — ≤1h
- [x] **T0.5** 核对 Flyway 识别到的迁移数量与版本恰为 001~012 — ≤30min

> **T0 实施记录（阶段零三次推翻假设，这是本变更最有价值的产出）**：
>
> | 实测 | 结果 | 处置 |
> |------|------|------|
> | 空库启动 | ❌ 失败于 `SensitiveWordServiceImpl.@PostConstruct` 查 `sensitive_word` 表不存在，**与 Flyway 无关** | 修正 C4：基线导入是应用自举必经步骤（design.md §0 实测 4） |
> | parent 管理的 flyway 8.0.4 | ❌ `FlywayEditionUpgradeRequiredException: MySQL 5.7 is no longer supported by Flyway Community Edition` | **钉 7.15.0**，pom 注释写明理由与证据文件（§0 实测 1） |
> | `sql-migration-prefix`+`separator=-` | ✅ `Successfully validated 12 migrations`，含描述带 `-` 的 004/010 | ADR-001 成立，**零改名** |
> | `baseline-version=0` 在已导快照库上执行 | ❌ `Error 1060 Duplicate column name`（001 首个 ADD COLUMN） | **快照与增量互斥**，Flyway 改为「只往后记账」（§0 实测 3） |
> | 改写后的 004 由 Flyway 执行 | ✅ 夹具先 DROP 掉 8 列 → Flyway `004 im-complete success=1` → 8 列全部重建；再手工重跑 2 次 rc=0 | **ADR-005 成立** |
>
> **过程中我自己犯的两个错（均已修正）**：
> ① 手抄 004 时把 `file_type`/`reason`/`status` 的 COMMENT 从 **0-based 误改成 1-based**（终端编码乱码掩盖了差异，靠 `git diff` 逐行查删除行才发现）。改为**从 `git show HEAD:` 取原文 + 程序化替换**（`_rewrite_004.py`），保证未触及部分逐字节不变。
> ② 生成器对 `define` 里的引号**重复转义**（原文已是 `''` SQL 转义形态）→ 4 个连续引号 → `Error 1064`。去掉二次转义后通过。
>
> 证据文件：`2026-10-04-flyway-t0.3*.txt`、`2026-10-04-flyway-t0.4*.txt`（均在 `engineering/qa/`）

## 阶段一：门禁先红后绿

- [x] **T1.1** 新增 `scripts/verify/verify_migration_flyway.mjs`（**最终 54 项断言**）：断言
      ① `pom.xml` 引入 `flyway-core` 且**显式钉版本**（原计划写「未硬写版本号」，
         被实测 1 推翻——必须钉 7.15.0，理由见下），并校验该版本 major < 8
      ② `<resources>` 把 `easychat-migration-*.sql` 复制到 `db/migration`
      ③ `application.properties` 的 `locations` / `prefix` / `separator` / `validate-on-migrate` 正确
      ④ **`baseline-version` 等于仓库内最大迁移编号**（ADR-004 的核心断言）
      ⑤ 迁移文件**零 `DELIMITER`**、零 `CREATE PROCEDURE|FUNCTION|TRIGGER|EVENT`
      ⑥ `compose` 含 `migrate` service 且 backend 的 `depends_on` **结构化**解析出 migrate 条目
      ⑦ 前置脚本存在、含拒绝分支、以非 0 退出，且**优先读 env** 声明的版本号
      ⑧ 迁移编号连续且从 001 起；⑨ backend 与 migrate **共用同一** baseline-version 变量
      在**未改造**代码上实跑 exit=1（4 项红），改造后 **54/54 通过** — ≤1h
- [x] **T1.2** 补 `scripts/verify/mutation_migration_flyway.cjs`（沙箱副本 + 变异） — **14/14 捕获** — ≤40min
- [x] **T1.3** 接入 `ci.yml` 与 `pre-push` — ≤20min

> **T1 实施记录**：门禁最终 **54 项断言**。变异检验过程中**两次抓出「假有判别力」**，
> 若无变异本批会交付两个看起来是绿的无效断言：
> ① 换行不敏感匹配写成 `escapeRe` + `\n` 替换，在 CRLF 文件下锚点全落空却被判「变异未生效」；
> ② 门禁用了 PCRE 的 `\Z`，**JS 正则中它不存在**（是字面量 `Z`），前瞻永不成立，
>    拿到的「service 块」实为文件后半段 → 部分变异靠**不相干**断言变红。
> 修正后 14/14 均由各自该红的断言捕获。
> **另主动登记一条不可静态检测项**（拒绝分支条件反转无法从文本推出），
> 已从变异清单撤除该用例而非假装覆盖，改由 T2.3 真机三路径兜底。

## 阶段二：baseline 前置校验

- [x] **T2.1** **[TDD]** 新增前置脚本的比对逻辑：0 漂移 → 放行；有漂移 → 退出码 1 并打印差异清单；全新库 / 已有历史表 → 放行 — ≤1h
- [x] **T2.2** 实现 `scripts/migrate/preflight-baseline-check.mjs`（node 调 mysql CLI，解析逻辑与 `verify_schema_drift.mjs` 同源） — ≤1h
- [x] **T2.3** 在真库上验三条路径 — ≤40min

> **T2.3 结果**（②③ 刻意在**库副本** `ec_preflight_probe` 上做，不污染开发库）：
>
> | 路径 | 条件 | 结果 |
> |---|---|---|
> | ① | 真实开发库 0 漂移 + 版本=12 | ✅ 放行 exit=0，「26 张表全对齐」 |
> | ② | 副本少 `user_contact.remark` + 版本=12 | ✅ 拒绝 exit=1，精确列出该列并给二选一处置 |
> | ③ | 同上 + env `SPRING_FLYWAY_BASELINE_VERSION=9` | ✅ 放行 exit=0 |
>
> **路径③是靠修一个自曝 bug 才成立的**：脚本原先只读 `application.properties` 的默认值 12、
> 不读 env → 运维用 env 声明 9 时闸门仍按 12 判「声称最新版」，
> **把它自己报错信息里建议的处置路径堵死**。修正为优先读 env，门禁已加断言锁住。

## 阶段三：接入与文档

- [x] **T3.1** `docker-compose.yml` 增 `migrate` service（前置校验闸门），`backend` 依赖其 `service_completed_successfully`；两侧 baseline-version **必须同源** — ≤40min
- [x] **T3.2** 存量库启动全链路验证 — ≤1h
- [x] **T3.3** ~~全新库全链路验证~~ → **已被实测 2 否定并作废** — 见下方说明
- [x] **T3.4** `verify_schema_drift.mjs` 回归 — **0 ERROR / 1 WARN**（活库多出 `flyway_schema_history`） — ≤20min
- [x] **T3.5** 全量回归 — ≤40min
- [x] **T3.6** 文档同步 — ≤40min

> **T3.2 结果**：`Started EasyChatApplication in 6.257 seconds`，且
> `flyway_schema_history` 内容为 `1  12  << Flyway Baseline >>  BASELINE  1`
> —— **存量库走 baseline、未重跑 001~012**（正确，重跑会 1060）。
>
> **T3.3 作废说明**：原计划「全新库从零执行 001~012」被**实测 2 直接否定** ——
> 空库上应用在 Flyway 之前就崩于 `SensitiveWordServiceImpl.@PostConstruct` 查 `sensitive_word`。
> 即「靠迁移从空库建起全部表」需要先改多处 `@PostConstruct` 容错，属**独立变更**。
> 终态：**基线导入是应用自举的必经步骤**（与 compose 既有做法一致）。
> design.md 的 Capability **C4 已作废**。
>
> **T3.5 结果**：单测 **270/270**；`package` BUILD SUCCESS（jar 内 12 份迁移 + `flyway-core-7.15.0.jar`）；
> **15/15 门禁通过**；**5/5 变异脚本 exit=0**。
>
> **T3.6 结果**：`AGENTS.md` §6.4 新增第 4/5 条（客户端专有指令禁令 + Flyway 约定，
> 并在第 3 条上标注「本条与现状存在偏差」的实测警示）；`README.md` §4.2 改写为
> 「由 Flyway 自动执行」并写明互斥关系与纳管成本；`docs/system-facts.md` §14 #9 标记闭环、
> 新增 #10（非幂等迁移，已决策不做）与 #11（不可静态检测项），变更日志追加一行。

## 阶段四：收尾

- [x] **T4.1** `engineering/qa/2026-10-04-flyway-migration-automation.md` — ≤30min
- [x] **T4.2** `engineering/retro/2026-10-04-flyway-migration-automation.md`（四段式） — ≤30min
- [x] **T4.3** spec-delta 回写 → **新建 `openspec/specs/migration-automation/spec.md`**（不塞进 `runtime-config`：迁移执行与配置外置是不同能力） — ≤30min
- [x] **T4.4** `git mv` 归档至 `openspec/archive/2026-10-04-flyway-migration-automation`；按域拆提交 — ≤30min

## 遗留（本批明确不做）

| 项 | 原因 | 后续 |
|----|------|------|
| **6/12 迁移非幂等**（001/002/006/007/009/011） | 用户决策不修：Flyway 版本表已保证每个迁移只执行一次。**但已在 AGENTS §6.4-3 与 README §4.2 写明「补齐前不要手工重跑这六份」** | 独立 Change；004 已有 PREPARE/EXECUTE 现成范式 |
| 空库从零建表（需改多处 `@PostConstruct` 容错） | 实测证明与 Flyway 无关，属应用自举顺序问题 | 独立 Change |
| 前置校验的拒绝分支方向不可静态检测 | 静态断言的固有上限，**已主动登记而非假装覆盖** | 需改写为可注入依赖的模块 + 上测试框架（L3） |
| `--status` 只读模式 | 用户未勾选；需额外 CLI 参数解析 | 独立小 Change |
| CI 中对全新库跑「从零执行」冒烟 | 前提已被上一条否定（空库无法自举），CI 需加 MySQL service | 随「空库自举」一并处理 |
| 迁移文件迁入 `src/main/resources/db/migration/` | 与 AGENTS §6.4-2 命名约定冲突，需先改规范 | 需先改 AGENTS |
| compose 的 `migrate` 容器**未在容器内实跑** | `apk add` 需联网，沙箱无 Docker。门禁本身已实跑并 14/14 变异验证 | 人工在有 Docker 环境时验一次 |

## DoD 自检

- [x] `tasks.md` 全部勾选，且 **T0 的失败证据已留档**（含版本墙、1060、1064 三份）
- [x] `mvn -B clean test` 全绿（270/270）、`mvn -B package -DskipTests` 0 error
- [x] `baseline-version` 与最大迁移编号一致（门禁守护）
- [x] 存量库路径实测通过；**全新库路径已被实测证伪并作废**（非「未测」而是「测了，结论是不该测」）
- [x] 15 个门禁 + 5 个变异脚本全绿
- [x] `AGENTS.md` / `README.md` / `docs/system-facts.md` 已同步
- [x] 归档闭环完成