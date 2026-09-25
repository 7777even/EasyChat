# Tasks — 聊天记录导出

- 关联 Design: 2026-09-26-chat-record-export/design.md
- 创建日期: 2026-09-26
- 预估总工时: 3h

> 任务按实施顺序排列；单条 ≤2h。
> 项目无单测基建（既有 Change 均以 build + 冒烟 + 证据验收），本 Change 沿用同一口径。

## 阶段一：主进程导出能力

- [x] 1.1 `ChatMessageModel.js` 新增 `selectAllMessageList({sessionId})`：按 `session_id + user_id` 全量升序读取（不带分页、不带 `maxMessageId`），并导出该函数 — ≤30min
- [x] 1.2 新增 `src/main/exportChat.js`：`buildTxt()`（`[yyyy-MM-dd HH:mm:ss] 昵称: 内容`，媒体降级为 `[图片]/[视频]/[文件] 名`）、`buildCsv()`（BOM + 表头 + 引号转义 + 公式注入防护）、`exportChatRecord()`（`showSaveDialog` → `writeFileSync` → 返回 `{success,canceled,path,count}`）— ≤1.5h
- [x] 1.3 `ipc.js` 注册 `exportChatRecord` 通道（回传 `exportChatRecordCallback`）并加入 `export {}` — ≤30min

## 阶段二：渲染进程入口

- [x] 2.1 `Chat.vue` 会话右键菜单新增「导出聊天记录」项，触发格式选择（TXT / CSV）后下发 IPC — ≤30min
- [x] 2.2 `Chat.vue` 监听 `exportChatRecordCallback`（成功提示条数与路径 / 取消静默 / 失败提示），`onUnmounted` 移除监听 — ≤30min

## 阶段三：验证

- [x] 3.1 前端 build 通过（electron-vite 产出 0 error）— ≤15min
- [x] 3.2 Vue 单文件编译校验 + 契约门禁 + openspec 卫生门禁全绿 — ≤15min
- [x] 3.3 冒烟：导出 TXT / CSV 文件生成成功，内容条数与会话一致；CSV 用 Excel 打开中文不乱码（静态核对 BOM 与转义实现）；取消保存对话框不报错 — ≤30min（未运行时执行项见 QA 报告）

## 阶段四：收尾

- [x] 4.1 `engineering/qa/` 与 `engineering/retro/` 记录 — ≤30min
- [x] 4.2 spec-delta 回写 `openspec/specs/chat-record-export/spec.md`（新建 capability）+ `git mv` 归档至 `archive/2026-09-26-chat-record-export` — ≤30min

## DoD 自检（完成后逐项确认）

- [x] proposal 的 C1/C2/C3 与 spec-delta Requirement 一一对应
- [x] 后端零改动（契约门禁无漂移）
- [x] preload 零改动
- [x] build 通过、门禁全绿
- [x] 无新增生产依赖
