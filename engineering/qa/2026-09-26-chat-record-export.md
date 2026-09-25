# QA — 聊天记录导出（TXT / CSV）

- 关联 Change: `openspec/changes/2026-09-26-chat-record-export`（实施后归档）
- 日期: 2026-09-26
- 等级: L3

## 验收范围

| 项 | 内容 |
|----|------|
| 主进程 | `src/main/exportChat.js`（新增）：全量读库 + TXT/CSV 组装 + 保存对话框 + 落盘 |
| 数据层 | `src/main/db/ChatMessageModel.js`：新增 `selectAllMessageList`（全量升序） |
| IPC | `src/main/ipc.js`：新增 `exportChatRecord` 通道 + 回调；`src/main/index.js`：注册 |
| 渲染层 | `src/renderer/src/views/chat/Chat.vue`：会话右键菜单两个导出项 + 结果回调 |
| 后端 | **零改动**（契约门禁验证） |

## 验收口径

项目无单测基建，沿用既有 Change 口径：**build 通过 + 静态推理 + 可执行冒烟（桩掉外部依赖跑真实纯函数）**。

## 实际执行命令与结果

1. 前端构建
   ```
   node node_modules/electron-vite/bin/electron-vite.js build
   ```
   结果：`✓ built in 542ms`（main）/ `255ms`（preload）/ `12.68s`（renderer），0 error。

2. Vue 单文件编译校验（`@vue/compiler-sfc` parse + compileScript + compileTemplate）
   ```
   node ec-check-vue.mjs src/renderer/src/views/chat/Chat.vue
   ```
   结果：`OK / ALL PASS`。

3. 主进程产物包含性检查
   ```
   grep -c "exportChatRecord" out/main/index.js   → 4
   ```
   结论：导出逻辑已进主进程 bundle。

4. 导出逻辑冒烟（真实源码，桩掉 `electron`/`fs`/DB/store 四个外部依赖）
   ```
   node ec-export-smoke.mjs
   ```
   结果：**15 PASS / 0 FAIL**（原始输出见 `2026-09-26-chat-record-export-smoke.txt`）
   覆盖：条数正确、UTF-8 BOM、CSV 表头、**公式注入前置单引号**、双引号翻倍转义、单元格内换行消除、媒体消息降级占位符、TXT 时间昵称格式、用户取消静默、空会话报错、写盘异常不抛出、缺会话 ID 报错、非法文件名清洗。

5. 契约门禁
   ```
   node scripts/check-api-contract.mjs
   ```
   结果：83 路由 / 81 前端调用 / **0 漂移**（后端零改动得到验证）。

## 未运行项（如实记录）

- **真实 GUI 全流程**：启动 Electron → 右键会话 → 选路径 → 打开生成文件。原因：本环境无法驱动 GUI 与系统保存对话框。静态佐证：`dialog.showSaveDialog` 用法与既有 `src/main/file.js:455` saveAs 完全同构；落盘与结果回传链路经冒烟覆盖。
- **Excel 实际打开验证 CSV 中文不乱码**：未真机打开 Excel。静态佐证：冒烟断言 `charCodeAt(0) === 0xFEFF` 确认 BOM 已写入。
- **大会话（万级消息）性能**：未实测。静态判断：单会话本地消息通常为千级，且为一次性操作。

## 附带发现并修复的缺陷

**上轮新增的 5 个 IPC 通道从未在 `src/main/index.js` 注册** —— `onSaveOrUpdateMessage`、`onDelLocalMessage`、`onCopyText`、`onSetSessionNoDisturb`、`onSaveSessionDraft` 只在 `ipc.js` 导出、未调用注册函数，导致以下功能**运行时完全无效**：

- 云端漫游历史回写本地
- 多选删除 / 右键删除消息
- 消息复制到剪贴板
- 会话免打扰本地缓存
- 会话草稿本地缓存

已在本次一并补注册（`index.js` import 列表 + 启动初始化）。这是静态可证的缺陷：`grep -c onDelLocalMessage src/main/index.js` 修复前为 `0`。

## 结论

通过。核心导出逻辑经可执行的真实源码冒烟验证（15/15），构建与门禁全绿。GUI 端到端与 Excel 打开属未运行项，已如实记录并附静态佐证。
