# Retro — 聊天记录导出

- 关联 Change: `openspec/changes/2026-09-26-chat-record-export`
- 日期: 2026-09-26

## 做得好

1. **范围收敛得当**：刻意只做「本地 SQLite → 文件」，把云端全量漫游划到后续专项，使本变更后端零改动、协议零改动、可独立回退。
2. **安全细节前置**：CSV 公式注入防护与 UTF-8 BOM 在设计阶段（ADR-3/ADR-4）就定了，不是事后补——避免了「导出文件在 Excel 打开就中招 / 乱码」这类典型返工。
3. **冒烟用真实源码**：把 `exportChat.js` 的 4 个外部依赖替换为委托式桩后直接 import 原文件跑断言，验证的是**产品代码本身**而不是复制品，15 项断言全过。

## 问题

1. **发现上一轮 5 个 IPC 通道从未注册**（`onSaveOrUpdateMessage` / `onDelLocalMessage` / `onCopyText` / `onSetSessionNoDisturb` / `onSaveSessionDraft`）。这些功能「代码写完、构建通过、看起来完成」，实际运行时静默失效。
2. 第一版导出入口误用了 `proxy.Confirm` 的 `cancelfun`/`cancelText` 参数——该工具只支持 `message`/`okfun`/`showCancelBtn`/`okText`，点击取消不会触发 CSV 导出。

## 原因

1. 项目的 IPC 通道是**在 `index.js` 逐个显式调用注册函数**的，不是自动扫描。上一轮只在 `ipc.js` 里新增函数并加进 `export {}`，漏了 `index.js` 两处（import 列表 + 初始化调用）。构建完全无法发现这类问题——它既不是语法错，也不是引用错，纯属「注册遗漏」。
2. 写渲染层代码时**没有先读 `Confirm.js` 的实现**就假设了它的参数能力。

## 改进方案

1. **给「IPC 通道注册」加静态门禁**：在 `scripts/` 增加检查——扫描 `ipc.js` 导出的全部 `onXxx` 函数，断言每个都在 `index.js` 中被调用，缺失即报错。这类「注册遗漏」是本项目已实际踩到的坑，值得用脚本而非人工记忆来防。
2. **规则沉淀**：新增/修改工具方法前先读实现再调用；对 `Confirm`/`Message` 这类被高频调用的全局工具，优先复用既有参数，需要新能力时扩展工具而不是在调用点臆造参数。
3. **验收口径升级**：对「主进程新增 IPC 能力」，除 build 外必须增加「注册点存在性」检查（本次靠人工 grep 发现，应脚本化）。
