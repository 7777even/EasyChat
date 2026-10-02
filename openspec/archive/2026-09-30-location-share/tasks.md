# Tasks — 位置分享

- 关联 Design: 2026-09-30-location-share/design.md
- 创建日期: 2026-09-30
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## ⚠ 状态：已收尾并归档（2026-10-02）

原于 2026-09-30 归档，2026-10-01 复核发现「前端阶段完全未实施」而移回；2026-10-02 补齐前端并重新归档。

**实现方式经人工确认偏离原 design**：原 design §7 写「需要地图 API（如高德/百度）」，实测全仓无地图能力且接入 SDK 属新增依赖（L3）。2026-10-02 人工确认改为**不引地图 SDK**：

- 发送：地址文本输入 + 可选「获取当前位置」（`navigator.geolocation`）
- 展示：位置气泡（图标 + 名称 + 经纬度）→ 点击弹详情 → 「在地图中打开」用 `shell.openExternal` 打开高德 URI（无需 Key）
- 存储：`extra_data` = `{location, latitude, longitude}`，与 design §3 一致

## 阶段一：后端基础结构

- [x] 新增 `MessageTypeEnum.LOCATION(25)` — ≤15min
- [x] 修改 `ChatMessageServiceImpl` 支持位置消息 — ≤30min

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：前端

- [x] `MessageSend.vue` 添加位置选择功能（工具栏「位置」按钮 + 弹窗：地址输入 + 可选获取当前位置） — ≤1h
- [x] `ChatMessageLocation.vue` 位置消息展示组件（气泡 + 点击详情 + 在地图中打开） — ≤1h

## 阶段四：验证与同步

- [x] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [x] 同步 `engineering/qa/` 验证报告 — ≤30min
- [x] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段五：收尾

- [x] spec-delta 回写 `openspec/specs/location-share/spec.md` — ≤30min
- [x] 归档 Change 到 `openspec/archive/2026-09-30-location-share` — ≤15min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-30-location-share/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方（本变更无 DDL 变更；位置存 `extra_data`）
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
