# Tasks — 群二维码与群邀请

- 关联 Design: 2026-09-30-group-qrcode-invite/design.md
- 创建日期: 2026-09-30
- 预估总工时: 6h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已收尾并归档（2026-10-02）

原于 2026-09-30 归档，2026-10-01 复核发现「前端阶段完全未实施」而移回；2026-10-02 补齐前端、修复后端桩并重新归档。

- 前端：`GroupDetail.vue` 加「群二维码」「群邀请链接」按钮（仅群主/管理员可见）+ 两个弹窗（QR 图 + token + 复制）
- 后端：`getGroupIdByToken` 三处桩（`RedisComponet` + 两个 Service 私有方法）统一改为反查索引，join 接口由「永远失败」变为可用
- 依赖：新增前端 `qrcode` 包（纯 JS、无原生依赖），**已经人工确认**
- 活体验证：建群 → 生成二维码 → 非成员扫码加入成功；生成邀请链接 → 注册新用户 → 加入成功；重复加入/无效 token 正确拒绝

## 阶段一：后端基础结构

- [x] `RedisComponet` 新增二维码/邀请链接存储方法 — ≤30min
- [x] 新增 `GroupQrCodeService` — ≤1h
- [x] 新增 `GroupInviteService` — ≤1h
- [x] 新增 `GroupController` 接口 — ≤1h

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：前端

- [x] `GroupDetail.vue` 显示群二维码（群主/管理员可见按钮 → 生成 → 弹窗展示 QR 图 + token + 复制） — ≤1h
- [x] 群邀请链接展示（同页弹窗：生成 → token + 复制） — ≤1h
- [x] `RedisComponet.getGroupIdByToken()` 桩实现替换为真实反查 — ≤1h
  **（2026-10-02 修复）** 原实现 `return null`，且 `GroupQrCodeServiceImpl` / `GroupInviteServiceImpl` 各自还持有一份**私有桩**（同样返回 null），导致 join 接口永远失败。改为写入时同步反查索引（`easychat:group:qrcode:token:{token}` / `easychat:group:invite:token:{token}` → groupId，同 TTL 7 天），三处统一走 `RedisComponet.getGroupIdByToken`。

## 阶段四：验证与同步

- [x] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [x] 同步 `engineering/qa/` 验证报告 — ≤30min
- [x] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段五：收尾

- [x] spec-delta 回写 `openspec/specs/group-qrcode-invite/spec.md` — ≤30min
- [x] 归档 Change 到 `openspec/archive/2026-09-30-group-qrcode-invite` — ≤15min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-30-group-qrcode-invite/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方（本变更无 DDL 变更；新增 `qrcode` 前端依赖已经人工确认）
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
