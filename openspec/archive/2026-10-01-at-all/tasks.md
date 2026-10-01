# Tasks — @所有人

- 关联 Design: 2026-10-01-at-all/design.md
- 创建日期: 2026-10-01
- 预估总工时: 1.5h

## 阶段一：前端适配

- [x] MessageSend.vue 输入框新增@按钮 — ≤30min
  - 群聊才渲染（`isGroupChat`）；面板含「@所有人」项 + 群成员列表 + 搜索框
  - 成员与我的角色按 `getGroupInfo4Chat` 懒加载，同群缓存一次、切会话重置
  - 「@所有人」仅 role 0/1 渲染（`canAtAll`），并在 `selectAtAll` 内二次校验
  - @文本插入到光标位置（非文末），插入后恢复光标
- [x] ChatMessage.vue @所有人消息特殊样式 — ≤20min
  - `isAtAll`：`extraData.atAll` 或正文含「@所有人」双判，兼容旧消息
  - `.at-all-message` 气泡描边高亮 + `.at-all-tag` 标记红色加粗
  - 撤回/管理员删除态走原文，不套 @所有人 着色
- [x] Chat.vue 处理@所有人消息发送 — ≤20min
  - 经核实无需改动：发送链路（`sendMessageDo` → `sendMessage` 接口 → WS 广播）
    与 @成员提及完全同构，`extraData.atAll` 随既有 `extraData` 参数透传
  - 接收侧 `reciveMessage` 统一入口已透传整帧，无需新增分支
- [x] 前端构建通过 — ≤15min

## 阶段二：验证与同步

- [x] 前端构建通过 — ≤15min（`npm run build` 0 error）
- [x] ESLint 无新增 error — ≤10min（17 error 均为改动前既有）
- [x] 构建产物断言（at-all-tag / at-panel / --ec-at-all 均已产出） — ≤10min
- [x] 同步 engineering/qa/ 验证报告 — ≤15min

## 阶段三：收尾

- [x] 同步 engineering/retro/ 复盘记录 — ≤15min
- [x] spec-delta 回写 openspec/specs/at-all/spec.md + 归档 Change — ≤15min

## 实施偏差记录

| 计划落点 | 实际落点 | 原因 |
|---------|---------|------|
| Chat.vue 处理发送 | 无改动 | 发送链路与 @成员提及同构，标记走既有 `extraData` 透传；新增分支只会是死代码 |
| 无 | `base.scss` 新增 `--ec-at-all` | 深浅色主题需成对定义（`html.dark` 覆盖），沿用既有变量体系 |

## DoD 自检（完成后逐项确认）

- [x] tasks.md 全部勾选
- [x] 前端构建通过
- [x] 归档闭环完成
- [x] QA / Retro 记录已落 engineering/
