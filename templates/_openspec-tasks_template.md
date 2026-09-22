# Tasks — <变更名称>

- 关联 Design: <YYYY-MM-DD-<name>/design.md>
- 创建日期: <YYYY-MM-DD>
- 预估总工时: <N>h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段一：基础结构

- [ ] **[TDD]** 新增 / 修改 Entity、Mapper、XML，同步 `easychat.sql` — ≤1h
- [ ] **[TDD]** 新增 / 修改 Service 业务规则 — ≤2h

## 阶段二：接口与契约

- [ ] **[TDD]** 新增 / 修改 Controller 端点（路由 + @Valid + 错误码落地） — ≤1h
- [ ] 后端 mvn compile 通过 — ≤30min

## 阶段三：前端适配

- [ ] **[TDD]** 渲染进程：Vue 组件 / Pinia store 改动 — ≤2h
- [ ] 渲染进程：request.js 调用对齐后端 Result<T> 包络 — ≤1h

## 阶段四：验证与同步

- [ ] 后端启动冒烟：覆盖新增端点 + 关键链路 — ≤1h
- [ ] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [ ] 同步 `engineering/qa/` 验证报告（含 curl 证据） — ≤30min

## 阶段五：收尾

- [ ] 同步 `engineering/retro/` 复盘记录（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [ ] spec-delta 回写 `openspec/specs/<capability>/spec.md` +归档 Change — ≤30min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/<YYYY-MM-DD-<name>>/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
