# Tasks — 状态

- 关联 Design: 2026-09-30-user-status/design.md
- 创建日期: 2026-09-30
- 预估总工时: 3h

## 阶段一：后端基础结构

- [x] 新增 user_status 表 + easychat.sql 同步 — ≤15min
- [x] UserStatus.java 实体 — ≤10min
- [x] UserStatusMapper.java + XML — ≤15min
- [x] UserStatusService.java 接口 — ≤10min
- [x] UserStatusServiceImpl.java 实现 — ≤30min
- [x] UserStatusController.java 接口 — ≤15min
- [x] 后端 mvn compile 通过 — ≤15min

## 阶段二：前端适配

- [x] Api.js 新增状态 API — ≤5min
- [x] UserDetail.vue 显示好友状态 + 设置自己的状态 — ≤30min
- [x] 前端构建通过 — ≤15min

## 阶段三：验证与同步

- [x] 后端启动冒烟：状态设置 + 查询 + 过期 — ≤30min
- [x] 前端构建通过 — ≤15min
- [ ] 同步 engineering/qa/ 验证报告 — ≤15min

## 阶段四：收尾

- [ ] 同步 engineering/retro/ 复盘记录 — ≤15min
- [ ] spec-delta 回写 openspec/specs/user-status/spec.md + 归档 Change — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] tasks.md 全部勾选
- [x] mvn compile 0 error
- [x] 前端构建通过
- [ ] 归档闭环完成
- [ ] QA / Retro 记录已落 engineering/
