# QA — 新消息提醒：任务栏图标闪烁（含最小化交替闪动修订）

- 日期: 2026-09-24
- 效率等级: L3
- 范围: `easychat-front/src/main/notification.js`（新增）、`wsClient.js`、`ipc.js`、`index.js`、`renderer .../setting/UserInfo.vue`；`docs/system-facts.md`；`openspec/changes/2026-09-24-desktop-notification/` 四件套。后端 `easychat-java/`、preload、`Chat.vue`、`Tables.js` 零改动。

## 验收口径

- tasks 4.1：前端 `npm run build`（electron-vite 三段）0 error。
- tasks 4.2 冒烟 8 项：失焦/最小化闪烁表现、聚焦不触发、开关启停、托盘 `hide()` 已知边界。
- tasks 4.3 回归 4 项：`sysSetting.localFileFolder` 键不丢、自身回声不闪、>5min 补推不闪、ACK/SYNC/撤回系统帧不闪。
- DoD：四件套闭环（spec-delta 回写 specs/ + 归档）、`docs/system-facts.md` 同步、QA/Retro 落 `engineering/`。
- 安全约束：开关写入整份读-改-写 + 键白名单，仅按当前用户自己的设置行定位；不改接口契约、不改 WS 包络、不加 npm 依赖。

## 实际执行命令与结果

- `npm run build`（easychat-front，收尾重跑）：main/preload/renderer 三段 built，**0 error** — 日志见同目录 `2026-09-24-desktop-notification-build.evidence.txt`（历史共执行 3 次，均通过）。
- 静态走查（grep 全仓）：`new Notification` / `notificationClicked` / `notifyNewMessage` 残留 **0 处**；`flashFrame` 仅存在于 `notification.js` 门禁（1 处触发 + 停闪清理）与 `index.js` 既有获焦停闪。
- 导入对齐核查：`stopBlink` 调用方（`index.js`）与导入一致；`ipc.js` 无用导入已移除——修复了会话中断点遗留的错位（若不修复，获焦时将抛 `stopBlink is not defined`；bundler 对未导入标识符不报错，build 无法暴露）。
- `git diff Chat.vue` = 0（零改动确认）。

## 未运行项

- **tasks 4.2 冒烟 8 项未系统执行**：运行时 GUI 验证依赖人工双账号操作，2026-09-24 人工当场确认收尾（"ok 就这样吧"），按人工豁免记录，不计为通过。已发生的人工实测仅为：用户实测最小化/失焦表现并反馈「最小化仅任务栏背景变红、无闪动」，该反馈驱动实现修订 2（交替闪烁循环）；**修订后未复测**。
- **tasks 4.3 回归 4 项未执行**（同上豁免）；其中「系统帧不闪」「自身回声不闪」有静态走查佐证（白名单 + 调用点位置），但运行时行为未实测。
- **任务栏闪烁截图 / 开关截图未采集**：AI 环境无 GUI 截图能力，人工未提供。

## 证据附件

- `2026-09-24-desktop-notification-build.evidence.txt` — build 通过日志（本目录；`*.log` 被 .gitignore 排除，按 `*.evidence.txt` 惯例入库）。
- 本报告「实际执行命令与结果」中的 grep 走查结论。
- 截图证据：**缺失**（见未运行项）。

## 结论

- 构建与静态走查达成；**运行时冒烟与截图证据未达成（人工当场豁免，非通过）**。遗留建议：下次发布前按 tasks 4.2（8 项）/4.3（4 项）复跑并补截图，重点项 ⑦ 最小化交替闪动与回归项 `localFileFolder` 键保留。
