# Tasks — MD5 → BCrypt 密码加密升级

- 关联 Design: 2026-09-30-password-bcrypt/design.md
- 创建日期: 2026-09-30
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已收尾并归档（2026-10-02）

原于 2026-09-30 归档，2026-10-01 复核发现收尾缺失而移回；2026-10-02 由 `2026-10-01-password-handoff-unify` 变更补齐并重新归档：

- **活体验证**已补做（登录 / 二次登录 / 拒收 MD5 摘要 / 改密往返全通过）
- **前端口径**已统一（登录不再发 MD5，详见该变更 ADR-001）
- **`password` 列宽**已由 `easychat-migration-010-password-and-im-tables.sql` 在存量库执行（此前只改基线，导致登录 500）
- **批量迁移接口**任务取消，理由见阶段二
- 本变更原 QA 结论「功能正常」已由 `engineering/qa/2026-09-30-password-bcrypt.md` 末尾追加订正

## 阶段一：基础结构

- [x] 新增 `PasswordEncoder` 工具类（BCrypt 实现） — ≤1h
- [x] 修改 `user_info.password` 字段长度为 60 — ≤15min
- [x] 修改 `UserInfoServiceImpl` 注册/改密使用 BCrypt — ≤1h
- [x] 修改 `UserInfoServiceImpl` 登录支持双验证 — ≤1h

## 阶段二：迁移服务

- [x] 登录时自动迁移 MD5 密码到 BCrypt — ≤1h
- [x] ~~新增管理端批量迁移接口~~ — **取消（2026-10-02，人工确认）**：登录时自动升级已覆盖全部活跃账号；批量接口需明文才能重写，对沉睡账号无收益、对其余账号能力弱于登录升级；`md5(md5(x))` 双哈希账号本就无法反推明文，只能走邮箱找回密码。详见 `openspec/archive/2026-10-01-password-handoff-unify/design.md` ADR-006

## 阶段三：验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min
- [x] 手动验证登录/注册/改密 — ≤30min（**2026-10-02 由 `password-handoff-unify` 变更补做并通过**：活体真连库验证登录 / 二次登录 / 拒收 MD5 摘要 / 改密往返，证据见 `engineering/qa/2026-10-02-password-handoff-live.txt`。本变更当时的 QA 结论已由该报告追加订正）

## 阶段四：收尾

- [x] spec-delta 回写 `openspec/specs/password-bcrypt/spec.md` — ≤30min
- [x] 归档 Change 到 `openspec/archive/2026-09-30-password-bcrypt` — ≤15min
- [x] 同步 `engineering/qa/` 验证报告 — ≤30min
- [x] 同步 `engineering/retro/` 复盘记录 — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-30-password-bcrypt/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方（`password` 列宽已由 `easychat-migration-010-password-and-im-tables.sql` 在存量库执行；前端口径由 `password-handoff-unify` 统一）
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
