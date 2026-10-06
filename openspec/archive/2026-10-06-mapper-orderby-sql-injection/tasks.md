# Tasks — 收口 Mapper 的 `${}` 字符串拼接，并把排序改为列名枚举白名单

- 关联 Design: `2026-10-06-mapper-orderby-sql-injection/design.md`
- 创建日期: 2026-10-06
- 预估总工时: 9h（较初版 +2h：枚举白名单比「写死字面量」多一个契约）
- 状态: **人工确认已通过（2026-10-06），可进入实施**

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段零：门禁先落地并证明有判别力

- [x] 新增 `scripts/verify/verify_sql_concat_guard.mjs`，实跑并贴出退出码
      — **已完成，exit=1，如实报出 17 处**；行号与独立 grep 逐条一致 — ≤1h
- [x] **[TDD]** 先造 3 条「门禁本该抓住」的反例并确认它报出来（AGENTS §2.1 第 14 条）：
      ① 把某处 `<choose>` 改回 `${query.orderBy}` → 门禁转红
      ② 把 Mapper 文件名改错使门禁扫不到 → 门禁报「实扫少于 10 个」而非通过
      ③ 把注释剥离逻辑改坏 → 门禁的解析器自检项转红 — ≤1h
- [x] 门禁加断言：**排序白名单 `SortOption` ↔ XML `<when>` 分支双向对账**（A 无悬空引用 / B 无遗漏分支 / C 片段逐字一致 / D 不串表 / C3 裸字面量须在白名单内） — ≤1h
- [x] 反例检验（替代原计划的独立变异脚本，改为**逐条实跑反例 + 还原**）：
      ① 分支 SQL 与枚举不一致 → C1 转红 ② 枚举加了分支没加 → B 转红
      ③ 分支串表（`group_info` 分支写进 `MomentMapper`）→ D 转红；三次还原后均回绿 — ≤1h

## 阶段一：排序白名单（枚举）

- [x] **[TDD]** 先写失败单测：枚举 `fromHttp(String)` 对「白名单内值」返回对应枚举项；
      对「`id desc`」「`(select 1)`」「空串」「`create_time desc`（含空格与方向）」**抛
      `BusinessException(CODE_1001)`**（ADR-004：显式报错，**不静默回退**）
      — **实跑红阶段：16 例 6 failures + 8 errors 全指向桩**；填实现后 16/16 绿 — ≤1h
- [x] 新增排序白名单枚举 `SortOption`（22 项 / 17 张表）：列名 + 方向 → SQL 片段字面量；
      **逐一映射 design.md §7 的 11 类现有排序值**，多列排序（`role asc, create_time asc`）
      作为固定组合项；另按 C3 断言补入 `emoji`（`EmojiMapper.xml` 的排序此前从未入册） — ≤2h
- [x] `BaseParam`：**彻底删除** `orderBy`（含 `EmailVerifyCodeQuery` / `MomentNotifyQuery`
      的重复字段），新增 `sortField` / `sortDirection` / `sortOption` — ≤1h
      > 删字段而非收窄可见性：漏改的调用点会**编译失败**，其中 `UserContactBlacklistTest`
      > 里就藏了一处 `getOrderBy()` 断言 —— 若只是改名，该断言会静默通过而失去保护。
- [x] `mvn compile` 通过（24 处 `setOrderBy` 调用点编译失败，逐个暴露，无一遗漏） — ≤30min

## 阶段二：Mapper XML 收口（17 处）

- [x] **[TDD]** 先只改 1 处并确认门禁在该状态下仍转红（证明断言不是「全量改完一起变绿」） — ≤30min
- [x] 逐个把 `${query.orderBy}` 改为 `<choose>` 枚举分支或字面量。
      **每处先读该 Mapper 的 `<select>` 上下文与现有 `setOrderBy` 值，禁止照抄相邻文件**
      — 16 处实 sites（`CallLogReadMapper` 那处是注释）：3 张多选表用 `<choose>`
      （`user_contact` 3 项 / `chat_message` 4 项 / `email_verify_code` 2 项），
      13 张单选表用字面量（`<choose>` 在单选下是死代码）。**门禁由 exit 1 转 exit 0** — ≤2h
- [x] 删除 `UserInfoMapper.xml` 的 `password` / `passwordFuzzy` 两个 `<if>`，
      并删 `UserInfoQuery` 的两个字段 + 四个访问器（遗留 #24） — ≤30min
- [x] `CallLogMapper.selectList` 全仓零调用（死代码）→ **保留但补注释说明**
      （删 selectList 会牵动 `BaseMapper` 泛型契约，超出本 Change 范围） — ≤30min
- [x] `mvn compile` + `check-api-contract.mjs --strict` 0 漂移 — ≤30min

## 阶段三：端点与 Service 收口

- [x] **[TDD]** 新增 `SortWhitelistResolutionTest` 8 例：`ArgumentCaptor` 实证排序项**真的落到 Mapper**、
      非法值抛 `CODE_1001` 且**不落到 Mapper**、未指定走默认项、服务端预设值不被覆盖
      — **实跑红阶段：8 例 7 红**；填实现后 8/8 绿 — ≤1h
      > 原计划的 `WebDataBinder` 断言改为活体实测（T6）：字段已从类上删除，
      > 「绑定器忽略它」在编译期即成立，单测的边际价值低于一次真实请求。
- [x] 3 个 Service 接入 `SortWhitelistTools.resolveSort(param, "<表名>")`；
      `loadUser` 去除 `password*`；`AdminUserInfoController` 移除显式 `setSortOption`
      （排序只由 Service 解析，非法值报错只有一条产生路径） — ≤1h
- [x] 两个可达端点补**默认排序**（修掉「orderBy 为空则无 ORDER BY」的既有缺陷）。
      另发现 `moment_media` / `chat_session` 修复前**也完全没有 ORDER BY**，一并补默认项 — ≤1h
- [x] 全量单测 exit 0：**433**（基线 408 + SortOption 16 + Service 8 + 1 条新断言） — ≤30min

## 阶段四：门禁接入（必须与阶段一~三同批）

- [x] `verify_sql_concat_guard.mjs` 在当前 main 上 **exit 0** 后，接入 `.github/workflows/ci.yml`
      与 `.git/hooks/pre-push`；同步删掉 AGENTS §10 与 `scripts/README.md` 的「暂未接入」标注。
      ⚠️ **改的是 `setup-git-hooks.mjs` 生成器（sh + bat 两套）而非 `.git/hooks` 本身** ——
      只改后者的话，别人重装 hook 就丢。已重装本地 hook 验证含新门禁 — ≤30min
- [x] 全量门禁复跑 **21/21 exit 0**（含需活库的 `verify_schema_drift.mjs`，确认未误伤） — ≤30min

## 阶段五：验证

- [x] **活库注入实测（DoD 硬项）**：`scripts/smoke/smoke_sort_whitelist.py` **23/23 通过**
      - **对照实验先行**：先证明 5 个注入载荷在 MySQL 层**确实可执行**
        （`(select group_id from group_info limit 1)` / `(select sleep(3))` /
        `create_time desc -- ` 等），再用**同一批载荷**打修复后的接口 — ≤1.5h
      - T1/T2：3 端点 × （10 个 `sortField` + 4 个 `sortDirection`）= **42 个注入尝试
        全部 `CODE_1001`**
      - T4：合法排序生效，`/admin/loadUser` asc/desc 行序确实不同
      - T7 负向：普通用户仍 `CODE_1003`，鉴权未被削弱
      > ⚠️ 对照实验首版**自己就站不住**：选的 `(select 1 from information_schema.tables)`
      > 报 `ERROR 1242`（子查询多行）、`id desc` 报 `ERROR 1054`（`group_info` 无 `id` 列）
      > —— 那是「载荷不成立」而非「被拦住」。已换成在目标表上确实成立的载荷。
      > ⚠️ 证据边界：**未实测修复前的接口**（代码已改）。「修复前可注入」由
      > 对照实验 + 代码级可达性共同佐证，非端到端跑旧版本。
- [x] 活库核查 `/admin/loadUser`：`password` / `passwordFuzzy` 请求被绑定器忽略，
      XML 剥注释后核对无残留查询条件 — ≤30min
- [x] 活库核查分页稳定性：3 端点各重复请求 3 次，行序完全一致（C4） — ≤30min
- [x] 前端 `npm run test`（**58/58**）+ `npm run build` exit 0 —— 本次前端**零改动**，
      按 §2 矩阵仍实跑以证明未误伤 — ≤30min

## 阶段六：收尾

- [x] 同步 `engineering/qa/2026-10-06-sort-whitelist-sql-injection.md`
      + 5 份证据落 `engineering/qa/evidence/`（活体注入日志、门禁最终输出、3 条反例失败输出） — ≤30min
- [x] 同步 `engineering/retro/2026-10-06-sort-whitelist-sql-injection.md`
      （做得好 / 问题 / 原因 / 改进方案 + 5 项遗留） — ≤30min
- [x] 回写 `docs/system-facts.md` §14：**已删除 #23 / #24 两行**（登记纪律要求修复后删行，非加删除线）
      + 变更日志追加一行 + **新登记 #26 / #27** — ≤30min
- [x] spec-delta 回写 `openspec/specs/sql-safety/spec.md`（新建 capability）+ `git mv` 归档 — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵执行：`mvn compile` BUILD SUCCESS / `mvn test` **433** 0 error
      / 前端 `npm run test` 58 + `build` exit 0
- [x] **活体注入实测已完成并留证**（人工决策明确要求）：`smoke_sort_whitelist.py` **23/23**，
      3 端点 × 14 载荷 = **42 个注入尝试全部 `CODE_1001`**；对照实验先证明 5 个载荷
      在 MySQL 层确实可执行 —— 证据 `engineering/qa/evidence/2026-10-06-sort-injection-live.txt`
- [x] `verify_sql_concat_guard.mjs` 已在 CI 与 pre-push 生效（改 `setup-git-hooks.mjs` 生成器 + 重装验证），
      判别力由 3 条实跑反例证明（还原后全绿）
- [x] 排序**能力未被移除**（人工决策要求保留），改为枚举白名单；
      非法值**抛 `CODE_1001`**（ADR-004）
      > ⚠️ 本行原措辞为「非法值回退默认」，已被 2026-10-06 人工决策推翻，故同步订正。
- [x] 排序枚举 ↔ XML 分支**双向对账**（门禁 A/B/C1/C2/C3/D 六项断言）
- [x] 分页列表**恒有 ORDER BY**（C4）：3 端点活体重复 3 次行序一致；
      另补 `moment_media` / `chat_session` 两处原本完全无排序的缺陷
- [x] §14 遗留 #23 / #24 **已删行**（不是加删除线）
- [x] 归档闭环完成（spec-delta 回写 `openspec/specs/sql-safety/spec.md` + `git mv` 到 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
- [x] 已知不可静态检测项已按三字段登记（盲区 + 兜底手段 + 兜底实测证据）
