# Tasks — MD5 → BCrypt 密码加密升级

- 关联 Design: 2026-09-30-password-bcrypt/design.md
- 创建日期: 2026-09-30
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已从 archive/ 移回，变更未完成（2026-10-01 复核）

原于 2026-09-30 归档，但复核发现**收尾阶段未完成**，与 AGENTS §7.1「全勾必归档」不符，
故移回 `changes/` 继续执行。当前进度：**核心加密能力已上线，两项收尾缺失**。

已落地（复核确认）：
- `PasswordEncoder`（BCrypt）、`user_info.password` 扩至 60
- `UserInfoServiceImpl` 注册 / 登录 / 改密走 BCrypt，并对历史 MD5 密码**双验 + 自动升级**
- QA 报告 `engineering/qa/2026-09-30-password-bcrypt.md` 已如实记录未完成项

**未完成**（复核确认）：
- 管理端批量迁移接口 —— `git grep -n "migrate" -- easychat-java/src/main/java/com/easychat/controller/` **无结果**；
  QA 报告「未运行项」已写明「批量迁移功能：未实现」
- 手动验证登录 / 注册 / 改密 —— 需起 MySQL + Redis 双账号环境，当前不具备

## 阶段一：基础结构

- [x] 新增 `PasswordEncoder` 工具类（BCrypt 实现） — ≤1h
- [x] 修改 `user_info.password` 字段长度为 60 — ≤15min
- [x] 修改 `UserInfoServiceImpl` 注册/改密使用 BCrypt — ≤1h
- [x] 修改 `UserInfoServiceImpl` 登录支持双验证 — ≤1h

## 阶段二：迁移服务

- [x] 登录时自动迁移 MD5 密码到 BCrypt — ≤1h
- [ ] 新增管理端批量迁移接口 — ≤30min

## 阶段三：验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min
- [ ] 手动验证登录/注册/改密 — ≤30min

## 阶段四：收尾

- [x] spec-delta 回写 `openspec/specs/password-bcrypt/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-password-bcrypt` — ≤15min
- [ ] 同步 `engineering/qa/` 验证报告 — ≤30min
- [ ] 同步 `engineering/retro/` 复盘记录 — ≤30min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-30-password-bcrypt/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
