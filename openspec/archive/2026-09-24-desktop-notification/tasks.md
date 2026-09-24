# Tasks — 新消息提醒：任务栏图标闪烁

- 关联 Design: 2026-09-24-desktop-notification/design.md
- 创建日期: 2026-09-24
- 需求修订: 2026-09-24 横幅 → 任务栏图标闪烁（任务文案已按修订稿更新）
- 预估总工时: 4h（修订后下调：移除内容组装/点击跳转/声音）

> 任务按实施顺序排列；单条 ≤2h。
> 项目无单测基建（既有 Change 均以 build + 冒烟 + 截图证据验收），本 Change 沿用同一口径，不引入新测试依赖。

## 阶段一：主进程闪烁能力

- [x] 1.1 新增 `src/main/notification.js`：`flashOnNewMessage(message, mainWindow)`——五条抑制规则（开关/聚焦/类型白名单 2·5·4/自身回声/5 分钟时效）→ `mainWindow.flashFrame(true)`（系统行为持续至获焦）；**无** Notification API、**无**内容组装、**无**点击回调 — ≤2h
- [x] 1.2 `wsClient.js`：`case 4`、`case 2/5` 收帧处调用 `flashOnNewMessage`（置于既有「自身回声跳过」分支之后）；**移除** `onmessage` 顶部对全部帧的无条件 `flashFrame`；`ipc.js` `openChat` 登录事件调用 `initNotifySwitch()` 读入内存缓存（缺省 true） — ≤1h
- [x] 1.3 横幅残余清除：`Notification` 调用、`notificationClicked` 双端链路（notification.js 回调 + Chat.vue 监听/清理）全部移除，全仓 grep 零残留 — ≤30min
- [x] 1.4 走查确认：ACK(-1)/SYNC/心跳/撤回(14)/系统帧不触发闪烁（白名单拦截）；除门禁内一处外无其他 `flashFrame` 调用点残留 — ≤30min
- [x] 1.5 最小化闪动修复（实现修订 2）：最小化场景由单次 `flashFrame` 改为主动交替闪烁循环（`startBlink`/`stopBlink`，600ms 周期，获焦/关开关停止并清态）；非最小化保持单次高亮 — ≤1h

## 阶段二：开关链路

- [x] 2.1 `ipc.js` 集中注册 `ipcMain.on('updateSysSetting')`：整份 JSON 读-改-写 + 键白名单（仅合入 `notifySwitch`，`Boolean()` 归一）、按 `store.getUserId()` 定位行；成功后 `setNotifySwitch()` 刷新主进程内存缓存 — ≤1h
- [x] 2.2 `UserInfo.vue`（账号设置页，showType==0 详情区、个性签名行下）新增「新消息通知」el-switch：挂载时 `getSysSetting` 读（`notifySwitch` 缺省 true）→ 切换即发 `updateSysSetting`，保存失败回弹原值；卸载时移除监听；文案为闪烁语义 — ≤1h

## 阶段三：链路清理（原点击跳转，随需求修订改为移除）

- [x] 3.1 `Chat.vue` 移除 `notificationClicked` 监听、onMounted 注册与 onUnmounted 清理，还原为零改动 — ≤30min

## 阶段四：验证证据

- [x] 4.1 前端 build 通过（`npm run build` / electron-vite 产出 0 error，日志入 `engineering/qa/`） — ≤30min
- [x] 4.2 冒烟：①失焦（非最小化）收单聊文本→任务栏按钮静态红底，聚焦后消；②群聊消息→同①；③媒体消息→同①；④聚焦时→不触发；⑤好友申请→同①；⑥关闭开关→不触发且消息/未读正常，重开恢复；⑦**最小化后收消息→任务栏图标交替闪动（600ms 亮/灭，微信式），点击唤回/聚焦后停**；⑧托盘 `hide()` 态→无任务栏按钮不闪属预期（记 QA 已知边界） — ≤1.5h **（人工当场豁免 2026-09-24，未系统执行，见 QA 未运行项；用户实测曾覆盖①⑦表现并驱动实现修订 2，修订后未复测）**
- [x] 4.3 回归断言：开关关闭/开启后 `sysSetting.localFileFolder` 键仍存在（不被覆盖丢）；自身发消息不闪；重连补推 >5min 旧消息不闪；ACK/SYNC/撤回等系统帧不闪（对比旧全帧行为确认收敛） — ≤1h **（人工当场豁免 2026-09-24，未执行，见 QA 未运行项；系统帧/回声项有静态走查佐证）**
- [x] 4.4 同步 `engineering/qa/` 报告（UI 改动必附**任务栏闪烁截图** + 开关截图 + 实际命令/用例数/未运行项） — ≤1h（报告已落 `engineering/qa/2026-09-24-desktop-notification.md`；**截图未采集**，已在未运行项如实记录）

## 阶段五：收尾

- [x] 5.1 同步 `engineering/retro/` 复盘（做得好 / 问题 / 原因 / 改进方案） — ≤30min
- [x] 5.2 spec-delta 回写 `openspec/specs/desktop-notification/spec.md`（新建 capability）+ `git mv` 归档本 Change 至 `archive/2026-09-24-desktop-notification` — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-24-desktop-notification/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵对应行执行，前端 build 0 error、冒烟 8 项 + 回归 4 项通过（截图证据入 QA）——**冒烟/回归/截图未执行，人工当场豁免（2026-09-24），QA 已如实记入未运行项，遗留复跑建议见 QA 结论**
- [x] 代码改动若改变契约 / 行为 / 数据结构，已同步 `easychat.sql` / `docs/system-facts.md` / 前端调用方（本变更：无契约/表结构变更；闪烁行为收敛与 `notifySwitch` 新键回写 system-facts）
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
