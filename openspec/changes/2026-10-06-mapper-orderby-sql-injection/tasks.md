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
- [ ] **[TDD]** 先造 3 条「门禁本该抓住」的反例并确认它报出来（AGENTS §2.1 第 14 条）：
      ① 把某处 `<choose>` 改回 `${query.orderBy}` → 门禁转红
      ② 把 Mapper 文件名改错使门禁扫不到 → 门禁报「实扫少于 10 个」而非通过
      ③ 把注释剥离逻辑改坏 → 门禁的解析器自检项转红 — ≤1h
- [ ] 门禁加断言：**排序枚举项数 == 对应 XML 的 `<when>` 分支数**（ADR-001 的负面后果对冲） — ≤1h
- [ ] 写 `mutation_sql_concat_guard.cjs`：故意改回 `${}` / 删掉一个 `<when>` 分支，确认门禁转红 — ≤1h

## 阶段一：排序白名单（枚举）

- [ ] **[TDD]** 先写失败单测：枚举 `fromHttp(String)` 对「白名单内值」返回枚举项、
      对「`id desc`」「`(select 1)`」「空串」返回默认项 — ≤1h
- [ ] 新增排序白名单枚举：列名 + 方向 → SQL 片段字面量；
      **逐一映射 design.md §7 的 11 类现有排序值**，多列排序（`role asc, create_time asc`）
      作为固定组合项 — ≤2h
- [ ] `BaseParam`：`orderBy` setter 收窄为包内可见；新增 `sortField` / `sortDirection` — ≤1h
- [ ] `mvn compile` 通过 — ≤30min

## 阶段二：Mapper XML 收口（17 处）

- [ ] **[TDD]** 先只改 1 处并确认门禁在该状态下仍转红（证明断言不是「全量改完一起变绿」） — ≤30min
- [ ] 逐个把 `${query.orderBy}` 改为 `<choose>` 枚举分支。
      **每处先读该 Mapper 的 `<select>` 上下文与现有 `setOrderBy` 值，禁止照抄相邻文件** — ≤2h
- [ ] 删除 `UserInfoMapper.xml` 的 `password` / `passwordFuzzy` 两个 `<if>`
      （先复验零调用方） — ≤30min
- [ ] `CallLogMapper.selectList` 全仓零调用 → 与 Service 确认后**删除该死代码**，
      或补一个注释说明为何保留 — ≤30min
- [ ] `mvn compile` + `check-api-contract.mjs --strict` 0 漂移 — ≤30min

## 阶段三：端点与 Service 收口

- [ ] **[TDD]** 新增单测：用 `WebDataBinder` 直接构造绑定器，断言
      `password` / `passwordFuzzy` **不可绑定**（字段已删 → 绑定器忽略）、
      `sortField` 非法值回退默认 — ≤1h
- [ ] 3 个端点改用 `sortField` / `sortDirection`；`loadUser` 去除 `password*` — ≤1h
- [ ] 两个可达端点补**默认排序**（修掉「orderBy 为空则无 ORDER BY」的既有缺陷，
      MySQL 不保证稳定序 → 翻页可能重复/漏行） — ≤1h
- [ ] 全量单测 exit 0（当前基线 408） — ≤30min

## 阶段四：门禁接入（必须与阶段一~三同批）

- [ ] `verify_sql_concat_guard.mjs` 在当前 main 上 **exit 0** 后，接入 `.github/workflows/ci.yml`
      与 `.git/hooks/pre-push`；同步删掉 AGENTS §10 该行的「当前 exit=1，暂未接入」标注 — ≤30min
- [ ] 其余 20 个门禁复跑 exit 0（确认未误伤） — ≤30min

## 阶段五：验证

- [ ] **活库注入实测（DoD 硬项，不接受「代码级可达」代替）**：
      对 `/admin/loadGroup` 与 `/admin/loadBeautyAccountList` 各发
      `sortField=(select 1 from information_schema.tables)`、`sortField=id desc`、
      `sortDirection=desc; drop table x` 等载荷，确认
      ① 不执行注入 SQL ② 非法值回退默认排序 ③ 合法值生效 — ≤1.5h
- [ ] 活库核查 `/admin/loadUser`：确认 `password` 列不可再被 WHERE 引用 — ≤30min
- [ ] 活库核查分页稳定性：同一页码重复请求 3 次，返回行序一致（验证 C4） — ≤30min
- [ ] 前端 `npm run test` + `npm run build` exit 0 — ≤30min

## 阶段六：收尾

- [ ] 同步 `engineering/qa/`（含活体注入的 curl 证据与终端输出快照） — ≤30min
- [ ] 同步 `engineering/retro/`（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [ ] 回写 `docs/system-facts.md` §14：**删除 #23 / #24 两行**（登记纪律要求修复后删行）
      + 变更日志追加一行 — ≤30min
- [ ] spec-delta 回写 `openspec/specs/sql-safety/spec.md`（新建 capability）+ 归档 Change — ≤30min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-10-06-mapper-orderby-sql-injection/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵执行，`mvn compile` / `mvn test` 0 error
- [ ] **活体注入实测已完成并留证**（人工决策明确要求，不接受代码级可达代替）
- [ ] `verify_sql_concat_guard.mjs` 已在 CI 与 pre-push 生效，且其自身变异检验全捕获
- [ ] 排序**能力未被移除**（人工决策要求保留），改为枚举白名单且非法值回退默认
- [ ] 排序枚举项数 == XML `<when>` 分支数（门禁已断言）
- [ ] 两个可达端点**恒有 ORDER BY**（C4 已修）
- [ ] §14 遗留 #23 / #24 已删行（不是加删除线）
- [ ] 归档闭环完成（spec-delta 回写 `specs/sql-safety/` + `git mv` 到 `archive/`）
- [ ] QA / Retro 记录已落 `engineering/`
- [ ] 已知不可静态检测项已按三字段登记（盲区 + 兜底手段 + 兜底实测证据）
