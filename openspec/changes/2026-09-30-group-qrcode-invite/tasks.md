# Tasks — 群二维码与群邀请

- 关联 Design: 2026-09-30-group-qrcode-invite/design.md
- 创建日期: 2026-09-30
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已从 archive/ 移回，变更未完成（2026-10-01 复核）

原于 2026-09-30 归档，但复核发现**前端阶段完全未实施**，与 AGENTS §7.1「全勾必归档」不符，
故移回 `changes/` 继续执行。当前进度：**后端已全部完成，前端为零**。

已落地（复核确认存在）：
- 后端：`GroupQrCodeService(Impl)` / `GroupInviteService(Impl)` / `GroupController` 4 个路由
  （`/qrCode/generate`、`/qrCode/join`、`/invite/generate`、`/invite/join`）
- `RedisComponet` 的 `saveGroupQrCode` / `getGroupQrCode` / `saveGroupInvite` / `getGroupInvite` / `getGroupIdByToken`

**未完成**（复核确认不存在）：
- `GroupInvite.vue` 群邀请链接展示 —— 文件不存在
- `GroupDetail.vue` 群二维码展示 —— `git ls-files easychat-front/src/renderer/src/views/contact` 无相关文件
- 4 个路由**均无前端调用方**（`check-api-contract.mjs` 报 4 个孤路由）

**已知实现缺陷**：`RedisComponet.getGroupIdByToken()` 当前是**桩实现**，直接 `return null`
（注释写明"遍历所有群组…实际生产环境可能需要更高效的方式"），故 `/qrCode/join` 与
`/invite/join` 即使前端补齐也无法真正入群，需一并处理。

## 阶段一：后端基础结构

- [x] `RedisComponet` 新增二维码/邀请链接存储方法 — ≤30min
- [x] 新增 `GroupQrCodeService` — ≤1h
- [x] 新增 `GroupInviteService` — ≤1h
- [x] 新增 `GroupController` 接口 — ≤1h

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：前端（**未开始**）

- [ ] `GroupDetail.vue` 显示群二维码 — ≤1h
- [ ] `GroupInvite.vue` 群邀请链接展示 — ≤1h
- [ ] `RedisComponet.getGroupIdByToken()` 桩实现替换为真实反查（当前 `return null`，导致 join 接口不可用） — ≤1h

## 阶段四：验证与同步

- [ ] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [ ] 同步 `engineering/qa/` 验证报告 — ≤30min
- [ ] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段五：收尾

- [ ] spec-delta 回写 `openspec/specs/group-qrcode-invite/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-group-qrcode-invite` — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-30-group-qrcode-invite/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
