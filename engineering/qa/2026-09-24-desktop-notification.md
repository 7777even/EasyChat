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

### 运行时冒烟补验（2026-09-24 下午，自动化）

方法：后端沿用既有实例；前端另起新代码 `npm run dev`（main PID 33640，inspector :5858）；经 CDP `ipcMain.emit('openChat', config)` 免密注入 test 账号建立 WS；`win.flashFrame` 经 CDP 包装探针记录 `时间戳:标志位`；窗口状态用 CDP `win.restore()/minimize()` 走真实路径驱动；帧差分为 PowerShell 截屏（任务栏按钮区 x=400..1650、y=H-56..H-4，通道差 >12）。完整日志与命令见同目录 `2026-09-24-desktop-notification-smoke.evidence.txt`，视觉证据 `2026-09-24-blink-evidence.png`。

| 用例 | 场景 | 结果 |
|------|------|------|
| 4.2 ⑦ 最小化交替闪烁 | `minimized=true focused=false` + msg 1843 | 探针 `:1/:0` 交替、间隔 614/606/610/607ms（共 ~210 次直至获焦）；任务栏 x1405-1444 相邻帧 6 组变化 40 列 + 同相位 0 对；截图 A/B 行 **通过** |
| 4.2 ⑦ 聚焦停闪 | restore+focus | 末次调用 `:0`（stopBlink 清理），前后 1.6s 两次读取数组一致无新调用；聚焦帧对 diff=no-change **通过** |
| 4.2 ④ 聚焦不触发 | `focused=true` + msg 1844/1845 | 1844 帧到达有客户端 stdout 旁证（lastMessage+本地落库），调用 210→210 **通过** |
| 4.2 ① 非最小化失焦静态高亮（rev2） | `minimized=false focused=false` + msg 1846 | 调用 210→211 **仅一条 `:1` 无循环**；密集采样 6 帧 @150ms 全 no-change（恒亮）；获焦后按钮区复原；截图 C 行 **通过** |
| 4.3 自身发消息不闪 | 自发 msg 1847（最小化未聚焦） | 单端仅收到会话同步广播（无 case2 消息帧），调用 211→211 **通过** |
| 4.3 系统帧不闪 | 全程心跳/ACK/同步帧持续 | 数分钟采集内探针调用仅出现在白名单消息时刻，系统帧零触发（静态：仅 case4/case2·5 接入口 + 白名单 [2,5,4]）**通过（静态为主、运行时一致）** |

## 未运行项

- **tasks 4.2 ⑤ 好友申请（类型 4）运行时未触发**：第三方账号 token 已过期（HTTP 400，body `code=901 登录超时`），仅存的 test/7710 互为好友无法产生申请推送；静态佐证齐全（`wsClient` case4 L112 走同一 `flashOnNewMessage` 入口、帧携带 `message.messageType` L106/L110、白名单含 4）。
- **tasks 4.2 ② 群聊 / ③ 媒体（类型 5）运行时未单独触发**：与已验证类型 2 同一 case2/5 接入路径（静态佐证）。
- **tasks 4.2 ⑥ 开关 UI 启停 + 设置页截图未执行**：渲染层登录需密码+验证码，自动化不可得；开关缺省开已由 `~/.easychatdev/local.db` 事实核查证实（无 `notifySwitch` 键→默认 true），写路径 `updateSysSetting` 读-改-写为静态佐证。
- **tasks 4.3 `localFileFolder` 键保留、>5min 补推不闪**：未运行时执行（静态：读-改-写+键白名单；`flashOnNewMessage` 过期规则）。
- **tasks 4.2 ⑧ 托盘 `hide()` 态**：已知边界（无任务栏按钮无可闪对象），未实测。
- 首轮外部 `ShowWindow` 最小化导致 `isFocused()` 卡真（方法论陷阱，见 evidence M1）产生的零变化截图为无效证据，已删除。

## 证据附件

- `2026-09-24-desktop-notification-build.evidence.txt` — build 通过日志（本目录；`*.log` 被 .gitignore 排除，按 `*.evidence.txt` 惯例入库）。
- `2026-09-24-desktop-notification-smoke.evidence.txt` — 运行时冒烟完整证据：CDP 状态序列、flashFrame 探针原始时间戳、逐帧像素 diff 数字、消息 messageId、帧到达旁证、未运行项原因、方法论备注。
- `2026-09-24-blink-evidence.png` — 任务栏按钮三态对比截图（A 最小化闪烁 OFF / B 最小化闪烁 ON / C 非最小化失焦静态高亮）。
- 本报告「实际执行命令与结果」中的 grep 走查结论。

## 结论

- **核心达成**：构建 + 静态走查 + 运行时冒烟 6 用例（4.2①④⑦、4.3 自身不闪、系统帧不闪、聚焦停闪）全部通过，任务栏闪烁与静态高亮均有 CDP 探针 + 像素 diff + 截图三重证据，原「豁免」项中冒烟核心与截图证据已补齐销项。
- **遗留（非豁免，如实未达）**：4.2②③⑤⑥⑧ 与 4.3 的 `localFileFolder`/`>5min` 两项未运行时执行，原因见未运行项（token 过期、渲染层登录不可得、同路径静态佐证），建议发布前用可用账号人工补 ⑤⑥ 两项。
