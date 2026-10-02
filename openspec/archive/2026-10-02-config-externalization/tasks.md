# Tasks — 运行时配置外置

- 关联 Design: 2026-10-02-config-externalization/design.md
- 创建日期: 2026-10-02
- 预估总工时: 5h

> 任务按实施顺序排列；单条 ≤2h。
> **[TDD]** 标记的任务必须先写失败测试再实现。

## 阶段一：公共基线拆分

- [x] **[TDD]** 新增 `scripts/verify/verify_no_hardcoded_secret.mjs`：扫描 `application*.properties` 断言 prod 基线不含真实凭据、断言敏感键均为占位符形式；先跑在**未改造**的 `application.properties` 上确认失败（红，实测 2/15 exit=1） — ≤1h
- [x] 重写 `application.properties` 为公共基线：`spring.profiles.active` 默认 `dev`；敏感项改 `${ENV:默认值}`；删除 `easychat.turn.*` 与 `easychat.admin-emails` 硬编码 — ≤30min

## 阶段二：环境 profile

- [x] 新增 `application-dev.properties`：DB/Redis 本地值 + 公共 TURN 联调凭据（与变更前取值完全一致，零行为变化） — ≤30min
- [x] 新增 `application-prod.properties`：TURN 留空 + 注释警示、`easychat.admin-emails` 留待注入、`spring.datasource.password` 无可用默认值 — ≤30min

## 阶段三：凭据注入载体

- [x] 仓库根新增 `.env.example`（占位值 + 逐项注释 + 安全提示 + 「Boot 2.6 不自动读 .env」纪律） — ≤30min
- [x] `.gitignore` 新增 `.env` / `.env.local` / `.env.*.local`；确认 `docker-compose.yml` 变量名与 profile 键一致 — ≤30min

## 阶段四：验证与同步

- [x] **[TDD]** 跑 `verify_no_hardcoded_secret.mjs` 转绿（实测 19/19 exit=0）；`mvn -B clean package -DskipTests` 0 error — ≤1h
- [x] **[TDD]** 实跑 `dev` 与 `prod` 两个 profile 各启动一次，核对生效值（dev 与变更前一致；prod 无注入时按设计启动失败并出现 `using password: NO`；prod 注入口令后正常起） — ≤1h
- [x] 同步 `docs/system-facts.md`（新增 §1.1 配置分层 + 依赖版本倒挂技术债 + 变更日志一行）+ `README.md` §4.5 配置说明与 §7 单实例约束 — ≤30min
- [x] 同步 `engineering/qa/2026-10-02-config-externalization.md`（含红/绿门禁输出与三组启动场景证据） — ≤30min

## 阶段五：收尾

- [x] 同步 `engineering/retro/2026-10-02-config-externalization.md`（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [x] 落实 Retro 中标记「立即」的四项跟进：① 门禁补敏感定义边界与非敏感白名单注释 ② compose 的 backend 加 `SPRING_PROFILES_ACTIVE` 缺省 prod ③ `CallService` 类注释写明多实例限制 ④ README §7 + compose 注释写明 backend 不可 `--scale` — ≤30min
- [x] spec-delta 回写 `openspec/specs/runtime-config/spec.md` + 归档 Change 至 `openspec/archive/2026-10-02-config-externalization/` — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/archive/2026-10-02-config-externalization/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵「pom.xml、依赖、构建配置」行执行，`mvn package -DskipTests` 0 error
- [x] 无表结构变更，`easychat.sql` 与迁移脚本无需改动（确认无遗漏）
- [x] 对外契约零变更，`scripts/check-api-contract.mjs --strict` 仍 exit 0
- [x] 归档闭环完成（spec-delta 回写 `specs/runtime-config/` + 移入 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
