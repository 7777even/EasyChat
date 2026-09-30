# Tasks — 语音消息与表情包

- 关联 Design: 2026-09-30-voice-emoji/design.md
- 创建日期: 2026-09-30
- 预估总工时: 8h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。

## 阶段一：后端基础结构

- [x] 新增 `emoji` 表 + `Emoji` 实体 + `EmojiMapper` — ≤1h
- [x] 新增 `EmojiController` / `EmojiService` — ≤2h
- [x] 新增 `MessageTypeEnum.VOICE(24)` — ≤15min
- [x] 修改 `ChatMessageServiceImpl` 支持语音消息 — ≤1h

## 阶段二：后端验证

- [x] 后端 `mvn compile` 通过 — ≤15min
- [x] 后端 `mvn test` 通过 — ≤30min

## 阶段三：前端语音消息

- [x] `MessageSend.vue` 按住说话录音功能 — ≤2h
- [x] `ChatMessageVoice.vue` 语音消息播放组件 — ≤1h
- [x] 主进程录音文件处理 — ≤1h

## 阶段四：前端表情包

- [x] `EmojiPicker.vue` 表情包选择器 — ≤2h
- [x] `MessageSend.vue` 集成表情包选择器 — ≤1h
- [x] 表情包管理界面 — ≤1h

## 阶段五：验证与同步

- [x] 前端 ESLint / Prettier / Vite build 通过 — ≤30min
- [ ] 同步 `engineering/qa/` 验证报告 — ≤30min
- [ ] 同步 `engineering/retro/` 复盘记录 — ≤30min

## 阶段六：收尾

- [ ] spec-delta 回写 `openspec/specs/voice-emoji/spec.md` — ≤30min
- [ ] 归档 Change 到 `openspec/archive/2026-09-30-voice-emoji` — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] `openspec/changes/2026-09-30-voice-emoji/tasks.md` 全部勾选
- [ ] 按 AGENTS.md §2 矩阵对应行执行，mvn compile / package 0 error
- [ ] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / 前端调用方
- [ ] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [ ] QA / Retro 记录已落 `engineering/`
