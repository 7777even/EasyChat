# QA — Flyway 迁移自动化（2026-10-04）

- 关联 Change：`openspec/changes/2026-10-04-flyway-migration-automation`（已归档至 `openspec/archive/2026-10-04-flyway-migration-automation`）
- 等级：**L4**（新增生产依赖 + DB 结构相关 + 部署配置基线）
- 结论：**通过**（含 2 项已登记的已知不可静态检测项）

## 一、范围与验收口径

把「迁移执行 + 记账」从「靠人记」变为自动化，并补上 `verify_schema_drift.mjs` 缺失的**执行**这一环。
验收口径来自 tasks.md 的 T0.1~T0.5、T1.1~T1.3、T2.1~T2.3、T3.1~T3.6。

## 二、实测证据（本批最有价值的产出：三次实测推翻了三次假设）

> tasks.md **阶段零刻意设计为「先实测再动手」**，排在所有实现之前。
> 结果确实推翻了我在 design 里押的三个假设。以下均为真机证据，非推断。

### 实测 1 ❌ Flyway 8.0.4 不支持 MySQL 5.7 → 钉 7.15.0

证据：`engineering/qa/2026-10-04-flyway-t0.3b-baseline-imported.txt`

```
Flyway Community Edition 8.0.4 by Redgate
Database: jdbc:mysql://127.0.0.1:3306/ec_flyway_probe (MySQL 5.7)
FlywayEditionUpgradeRequiredException: Flyway Teams Edition or MySQL upgrade required:
MySQL 5.7 is no longer supported by Flyway Community Edition
```

parent `spring-boot-starter-parent:2.6.1` 管理的是 8.0.4。而**本机开发库正是 MySQL 5.7**
（`docker-compose.yml` 用 `mysql:8.0`，两边本就不一致）→ 随 parent 走会让**开发者本地根本起不来**。
处置：钉 `7.15.0`（最后一个 Community 下同时支持 5.7 与 8.0 的版本），pom 注释写明理由与证据文件。

### 实测 2 ❌ 空库无法自举（与 Flyway 无关）

证据：`engineering/qa/2026-10-04-flyway-t0.3-delimiter-blocked.txt`

失败于 `SensitiveWordServiceImpl` 的 `@PostConstruct` 直接查 `sensitive_word` 表（表不存在），
**日志里零条 Flyway 运行时输出**。说明「靠迁移从空库建起全部表」需要先改多处
`@PostConstruct` 容错，属独立变更。**基线导入是应用自举的必经步骤**（与 compose 做法一致）。

### 实测 3 ✅ prefix + separator 组合可用（ADR-001 成立，零改名）

证据：`engineering/qa/2026-10-04-flyway-t0.4a-pinned-715.txt`

```
Flyway Community Edition 7.15.0 by Redgate
Database: jdbc:mysql://127.0.0.1:3306/ec_flyway_probe (MySQL 5.7)
Successfully validated 12 migrations (execution time 00:00.023s)
```

12 份全部识别，含描述中带 `-` 的 `004-im-complete` 与 `010-password-and-im-tables`
→ 依赖 Flyway「按第一个分隔符切分」的行为，该假设已实证。**AGENTS §6.4-2 的命名约定零改动**。

同时验证了 **fail-closed**：`baseline-on-migrate=false` 时非空库无历史表 → 启动失败报错。

### 实测 4 ❌ 快照与增量互斥 → Flyway 改为「只往后记账」

证据：`engineering/qa/2026-10-04-flyway-t0.4b-from-scratch-delimiter.txt`

```
Migrating schema `ec_flyway_probe` to version "001 - group-management"
Migration ... failed!  Error Code : 1060        ← Duplicate column name
```

**这是本变更最关键的一次修正**。根因：`easychat.sql` 是**最新快照**，
`easychat-migration-*.sql` 是**增量**，两者是**互斥的两条路**而非先后关系
——这正是 compose 只在首启导快照的原因，那个做法本身是对的。

顺带查出：**12 份迁移中 6 份明确非幂等**（001/002/006/007/009/011 含 `ADD COLUMN` 无守卫），
其中 `001` 注释自述「MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，此处按首次迁移处理」，
**与 AGENTS §6.4-3「迁移必须幂等」长期矛盾**。已登记为遗留 #10，本批按用户决策不修。

### 实测 5 ✅ `migration-004` 改写后可被 Flyway 真实执行（ADR-005 成立）

证据：`engineering/qa/2026-10-04-flyway-t0.4d-004-prepare-execute-ok.txt`（DB 状态比日志更硬）

夹具：导入基线后**先 DROP 掉 004 所加的 8 列**，只挂 004 一个迁移，`baseline-version=3`。

```
Flyway Community Edition 7.15.0
Successfully validated 1 migration
Successfully baselined schema with version: 3
Migrating schema `ec_flyway_004` to version "004 - im-complete"
```

DB 落库验证（`engineering/qa/_probe_flyway_004.py verify`）：

```
8 列中已存在 8 个: [chat_session_user.top_type, ..._no_disturb, ..._draft,
 user_contact.remark, ..._group_name,
 chat_message.extra_data, ..._at_user_ids, chat_message.duration]
history: 3 << Flyway Baseline >> 1 | 004 im-complete 1
```

**失败证据也留档**（`...t0.4c-...`）：`Error Code 1064 near '0未置顶 1置顶'''`
——根因在生成脚本对已转义引号**重复转义**，非 Flyway 限制。

幂等性：004 再手工跑 2 次 **rc=0**（列已存在时走 `SELECT 1` 空操作）。

## 三、T2.3 前置闸门三路径真机验证

| 路径 | 条件 | 结果 |
|---|---|---|
| ① | 真实开发库 0 漂移 + 版本=12 | ✅ 放行 exit=0，「26 张表全对齐」 |
| ② | **库副本**少 `user_contact.remark` + 版本=12 | ✅ 拒绝 exit=1，精确列出 `user_contact.remark`，并给出二选一处置 |
| ③ | 同上 + env `SPRING_FLYWAY_BASELINE_VERSION=9` | ✅ 放行 exit=0 |

> 路径②刻意在**库副本**上做（`ec_preflight_probe`），不动真实开发库——
> 造漂移需 `DROP COLUMN`，污染开发库代价过高。副本已清理。

**路径③是靠修一个自曝 bug 才成立的**：脚本原先只读 `application.properties` 的默认值 12、
不读 env。运维用 env 声明 9 时，闸门仍按 12 判定「声称最新版」→ **把它自己建议的处置路径堵死**。
修正为优先读 env。门禁已加断言锁住这一点。

## 四、存量库全链路（T3.2）

`mvn spring-boot:run` 对真实开发库 `easychat`（`baseline-on-migrate=true`）：

```
Flyway Community Edition 7.15.0 by Redgate
Tomcat started on port(s): 5050 (http) with context path '/api'
Started EasyChatApplication in 6.257 seconds
```

落库确认（`_probe_flyway_db.py real`）：

```
flyway_schema_history 存在数 = 1
history: 1  12  << Flyway Baseline >>  BASELINE  1
业务表总数 = 27（含 flyway_schema_history，业务表仍 26）
```

即：**存量库走 baseline，未重跑 001~012**（正确——它们早已手工执行过，重跑会 1060）。

## 五、回归（T3.4 / T3.5）

| 项 | 结果 |
|---|---|
| `mvn -B clean test` | **270/270 PASS**，BUILD SUCCESS |
| `mvn -B package -DskipTests` | BUILD SUCCESS；jar 内迁移文件 **12** 份、`flyway-core-7.15.0.jar` 1 份 |
| `verify_schema_drift.mjs` | **0 ERROR / 1 WARN**（WARN = 活库多出 `flyway_schema_history`，符合设计，exit=0） |
| 15 个门禁（含新增） | **15/15 通过** |
| 5 个变异脚本 | **5 个 exit=0**；其中 `mutation_migration_flyway.cjs` **14/14 捕获** |
| `verify_migration_flyway.mjs` | **54/54 PASS** |
| `check-openspec-hygiene.mjs` | 通过 |

## 六、变异检验：两次抓出「假有判别力」

> AGENTS §2.1 第 1 条要求「门禁须实跑有判别力」。本批两次证明**若无变异，交付的会是假门禁**。

1. **换行不敏感匹配写成 `escapeRe` + `\n` 替换** → `escapeRe` 先把换行转成反斜杠+`n` 的字面两字符，
   随后的 `/\\n/g` 匹配不到它们，正则里留下裸 `\n`，与 **CRLF 文件永远不匹配**。
   结果 6 个用例锚点全落空、被判「变异未生效」——**看起来像门禁抓到了，实际变异根本没发生**。
   改为「归一化 LF 后替换、再按原风格写回」。
2. **门禁里 `(?=^ {2}\S|\Z)` 的 `\Z` 在 JavaScript 正则中不存在**（PCRE 才有），
   JS 当字面量 `Z` 匹配 → 前瞻永不成立 → `*?` 吞到文件末尾，
   拿到的「backend 块」其实是文件后半段。结果「前置校验永不失败」与
   「backend/migrate 不同源」两个变异**是靠不相干的断言变红的**。
   改为按 `^  <service>:` 切块，并新增「services 段可被正确解析」自检断言。

修正后 14/14 全部由**各自该红的断言**捕获。

## 七、已知局限（不谎报为通过）

| 项 | 性质 | 说明 |
|---|---|---|
| 拒绝分支**条件方向**无法静态检测 | 静态断言的固有上限 | 把 `if (!missing) {放行}` 反写成 `if (missing) {放行}` 后，门禁 4 条断言全部照样通过（字面量一个没变）。**已从变异清单撤除该用例**（不做假覆盖），改由第三节三路径真机验证兜底 |
| 6/12 迁移非幂等 | 已决策不做 | 遗留 #10。已在 AGENTS §6.4-3 与 README §4.2 标注「补齐前不要手工重跑这六份」 |
| compose 的 `migrate` 容器**未在容器内实跑** | 未运行项 | `apk add mysql-client` 需联网，沙箱无 Docker 环境。**已按 §2.1 第 1 条要求实跑过门禁本身**（`verify_migration_flyway.mjs` 结构断言 + 14/14 变异），但容器内 `node` 脚本实际执行未验证 |
| 全新库从零执行 001~012 | **已被实测 2 否定** | 空库上应用无法自举（`@PostConstruct` 早于建表）。原 design 的 C4「全新库能从零执行」**已作废**，基线导入是必经步骤 |
| 存量库声明 `baseline-version=9` 后 Flyway 真实执行 010~012 | 未运行 | 该路径的前置校验（③）已验证，但未构造出「真实版本号为 9」的库做端到端验证 |

## 八、证据文件

| 文件 | 内容 |
|---|---|
| `2026-10-04-flyway-t0.3-delimiter-blocked.txt` | 空库启动失败（`@PostConstruct`） |
| `2026-10-04-flyway-t0.3b-baseline-imported.txt` | Flyway 8.0.4 拒绝 MySQL 5.7（钉版本的依据） |
| `2026-10-04-flyway-t0.4a-pinned-715.txt` | 7.15.0 + `Successfully validated 12 migrations` |
| `2026-10-04-flyway-t0.4b-from-scratch-delimiter.txt` | `Error 1060 Duplicate column name`（快照增量互斥） |
| `2026-10-04-flyway-t0.4c-004-prepare-execute.txt` | 004 改写后首次执行失败（引号重复转义） |
| `2026-10-04-flyway-t0.4d-004-prepare-execute-ok.txt` | 004 执行成功 |
| `_probe_flyway_004.py` / `_probe_preflight.py` / `_probe_flyway_db.py` | 实测夹具（建库、造漂移、查落库），非仓库测试 |

## 九、结论

**通过。** 迁移从「靠人记顺序」变为「启动自动执行 + 持久化记账」，
并把「只改基线忘迁移」这一两次真实事故的成因，从「靠自觉」升级为「机控阻断」。

须明确告知的**代价**：「这个存量库跑到第几号」仍需人**一次性**知道——
Flyway 能往后记账，**不能回溯历史**。该代价已写入 `application.properties` 注释、README §4.2、AGENTS §6.4-5 三处。