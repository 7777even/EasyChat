# Tasks — 收口 Mapper 的 `${}` 拼接与管理端查询对象的请求绑定面

- 关联 Design: `2026-10-06-mapper-orderby-sql-injection/design.md`
- 创建日期: 2026-10-06
- 预估总工时: 7h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。
> ⚠️ **当前状态：等待人工确认关卡通过，尚未开始实施。**

## 阶段零：门禁先落地（已部分完成）

- [x] 新增 `scripts/verify/verify_sql_concat_guard.mjs`，实跑并贴出退出码 — 已完成，**exit=1 如实报出 17 处**
- [ ] **[TDD]** 先造 3 条「门禁本该抓住」的反例并确认它报出来（AGENTS §2.1 第 14 条）：
      ① 把某处 `${}` 改回字面量拼接 → 门禁转红
      ② 删掉某端点的 `setOrderBy` 硬编码覆盖 → 门禁转红
      ③ **「扫不到任何 `${}`」时必须报「解析器空转」而非通过**（首版正则即栽在这里） — ≤1h
- [ ] 写 `mutation_sql_concat_guard.cjs`：故意把 `${}` 改回去 / 把字面量排序改回拼接，确认门禁转红 — ≤1h

## 阶段一：Mapper XML 收口

- [ ] **[TDD]** 先让门禁在「只改 1 处」状态下转绿（证明断言有判别力，不是全量重写后一起变绿） — ≤30min
- [ ] 逐个移除 **17 处** `${query.orderBy}`，排序改为 XML 内字面量；
      每处**先读该 Mapper 现状**再改，禁止照抄相邻文件的排序值 — ≤2h
- [ ] 删除 `UserInfoMapper.xml` 中 `password` / `passwordFuzzy` 两个 `<if>`（先确证零调用方） — ≤30min
- [ ] `mvn compile` 通过 — ≤30min

## 阶段二：Query 对象与端点收口

- [ ] **[TDD]** 新增单测：`BaseParam` / `UserInfoQuery` 的 `orderBy`、`password`、`passwordFuzzy`
      **不可从 HTTP 绑定**（用 `WebDataBinder` 直接构造 binder 断言不可绑定字段） — ≤1h
- [ ] `UserInfoQuery` 删除 `password` / `passwordFuzzy` 字段及 getter/setter — ≤30min
- [ ] 11 处列表端点改为白名单绑定（`@ModelAttribute` + 显式 setter，或显式入参 DTO） — ≤1h
- [ ] `mvn compile` + `check-api-contract.mjs --strict` 0 漂移 — ≤30min

## 阶段三：门禁接入（必须与阶段一、二同批）

- [ ] `verify_sql_concat_guard.mjs` 在当前 main 上 **exit 0** 后，接入 `.github/workflows/ci.yml`
      与 `.git/hooks/pre-push`；同步删掉 AGENTS §10 该行的「暂未接入」标注 — ≤30min
- [ ] `verify_migration_flyway.mjs` 等既有门禁复跑 exit 0（确认未误伤） — ≤30min

## 阶段四：验证

- [ ] **活库注入实测**：对 3 个端点各发一次 `orderBy=if(1=1,sleep(3),0)` 与
      `orderBy=(select 1 from information_schema.tables)`，确认修复后不再执行 — ≤1h
      （**这是把「代码级可达」升级为「实证」的唯一手段，AGENTS §2.1 第 12 条**）
- [ ] 活库核查 `passwordFuzzy` 预言机：确认修复后 `password` 列不可被 WHERE 引用 — ≤30min
- [ ] 全量后端单测 exit 0（当前基线 408） — ≤30min
- [ ] 20 个门禁全量复跑 exit 0 — ≤30min

## 阶段五：收尾

- [ ] 同步 `engineering/qa/`（含活体注入的 curl 证据与终端输出快照） — ≤30min
- [ ] 同步 `engineering/retro/`（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [ ] 回写 `docs/system-facts.md` §14：**删除 #23 / #24 两行**（登记纪律要求修复后删行）
      + 变更日志追加一行 — ≤30min
- [ ] spec-delta 回写 `openspec/specs/sql-safety/spec.md`（新建 capability）+ 归档 Change — ≤30min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-10-06-mapper-orderby-sql-injection/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵执行，`mvn compile` / `mvn test` 0 error
- [ ] **活体注入实测已完成并留证**（不接受「代码级可达」代替）
- [ ] `verify_sql_concat_guard.mjs` 已在 CI 与 pre-push 生效，且其自身变异检验全捕获
- [ ] §14 遗留 #23 / #24 已删行（不是加删除线）
- [ ] 归档闭环完成（spec-delta 回写 `specs/sql-safety/` + `git mv` 到 `archive/`）
- [ ] QA / Retro 记录已落 `engineering/`
- [ ] 已知不可静态检测项已按三字段登记（盲区 + 兜底手段 + 兜底实测证据）
