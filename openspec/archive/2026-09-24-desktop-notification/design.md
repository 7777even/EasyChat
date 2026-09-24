# Design — 新消息提醒：任务栏图标闪烁

- 关联 Proposal: 2026-09-24-desktop-notification/proposal.md
- 创建日期: 2026-09-24
- 需求修订: 2026-09-24 横幅方案撤销，改为任务栏图标闪烁（微信对齐）；本 design 为修订后版本。

## 1. 架构设计

现状：`wsClient.js` `ws.onmessage` 顶部已持有 `getWindow("main")`，**对全部帧**无条件失焦闪烁（`flashFrame(true)`）；消息经 `sender.send("reciveMessage", message)` 推渲染层。修订后方案：闪烁收敛为白名单新消息触发 + 开关门禁，渲染层无需知道闪烁存在（无点击跳转链路）。

### 三层交互时序图（§4 前端 AGENTS 要求）

```
后端 WS (5051)
   │  JSON 帧（messageType 2/5/4 …）
   ▼
主进程 wsClient.js  ws.onmessage
   ├─ [修改] 移除顶部对全部帧的无条件 flashFrame
   ├─ 既有: saveMessage / sender.send("reciveMessage")
   └─ [新增] notification.flashOnNewMessage(message, mainWindow)
          ├─ 抑制规则（见 §1.1）→ 命中则跳过
          ├─ 最小化 → startBlink(): 600ms 交替 flashFrame(false)/(true) 循环（§1.1.1）
          │             └─ 获焦 / 开关关 → stopBlink()
          └─ 非最小化失焦 → flashFrame(true) 单次系统高亮
                （读开关: 主进程内存 notifySwitch，登录时从 user_setting.sysSetting 读入）
   ▼
preload（零改动，不新增暴露面）
   ▼
渲染进程 Chat.vue / 设置页
   └─ 零新增监听（无 notificationClicked 链路）

渲染进程 UserInfo.vue（账号设置页）
   └─ 开关切换 → ipcRenderer.send('updateSysSetting', { notifySwitch })
          ← ipc.js 集中注册（红线合规）
          → UserSetting.updateSysSetting → SQLite user_setting.sysSetting
          → 成功回调后 setNotifySwitch() 刷新主进程内存缓存
```

### 1.1 抑制规则（按序判定，全部命中才触发）

1. 开关关闭（内存 `notifySwitch === false`）→ 跳过。
2. 主窗口已聚焦（`mainWindow.isFocused()`）→ 跳过（聚焦时消息直接可见）。
3. 消息类型白名单：仅 `2`（文本）、`5`（媒体）、`4`（好友申请）；ACK(-1)/SYNC(-2)/心跳(-4)/撤回(14)/系统帧(3/6/8/9/10/11/12…) 一律不触发。
4. 自身回声：`message.sendUserId === store.getUserId()` → 跳过（`case 2/5` 既有群聊跳过分支已覆盖，调用点置于该分支之后，另在门禁内二次防御多端同步回声）。
5. 过期消息（重连补推）：`Date.now() - message.sendTime > 5 分钟` → 跳过，防离线补推误闪。

### 1.1.1 闪烁实现（两态，2026-09-24 实现修订 2）

| 窗口状态 | 实现 | 视觉表现 |
|----------|------|----------|
| 最小化（`isMinimized()`） | **主动交替闪烁循环**：`setInterval` 600ms 周期交替调用 `flashFrame(false)`/`flashFrame(true)`，强制产生亮/灭动画 | 微信式图标闪动；获焦或开关关闭即 `stopBlink()` + `flashFrame(false)` 清态 |
| 非最小化失焦 | 单次 `flashFrame(true)`（系统 `FLASHW_TIMERNOFG`） | 任务栏按钮静态红底高亮（人工验收确认可接受） |

> 为何最小化要循环：Windows 对**单次** `flashFrame` 在最小化窗口上不产生闪动动画（仅静态高亮/不可见），单次调用语义无法满足「缩入最小化时图标闪烁」的微信对齐目标；交替 stop/start 是不改窗口生命周期、不引入新依赖的最小实现。

> 相对横幅方案的差异：删除「内容组装」与「3 秒节流」——闪烁无正文、循环幂等（`startBlink` 内去重），规则由 6 条收敛为 5 条。

### 1.2 内容组装

**无**。闪烁为纯窗口状态行为，不组装 title/body，不读取消息正文（原横幅方案的 title/body 映射表随需求修订整体移除）。

### 后端改动

无。Controller / Service / WS / Entity / SQL 全部不动。

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `main/notification.js`（新增） | 闪烁抑制规则 + `flashFrame` 调用（`flashOnNewMessage`） | 主进程纯辅助模块，不注册 ipcMain（红线：注册集中在 `ipc.js`） |
| `main/wsClient.js` | 移除 `onmessage` 顶部全帧 `flashFrame`；`case 2/5/4` 分支末尾调用 `flashOnNewMessage` | 只做调用接线，规则在 notification.js |
| `main/ipc.js` | 注册 `ipcMain.on('updateSysSetting')`（现状：`updateSysSetting` 已在 `UserSetting.js` 导出但**未注册通道**，本变更补上）；`openChat` 登录事件中调用 `initNotifySwitch()` | 集中注册合规 |
| `preload/index.js` | **零改动** | 规避 §5 L4 |
| `renderer/views/setting/UserInfo.vue`（账号设置页；`Setting.vue` 仅为菜单壳无内容区） | 「新消息通知」el-switch，控制闪烁，读写 sysSetting | UI + 组装 patch |
| `renderer/views/chat/Chat.vue` | **零改动**（横幅点击跳转监听已随需求修订移除，还原原状） | — |

## 2. 接口设计

无对外接口变更（无 HTTP 端点、无 WS 包络变更；进程内 IPC 仅 `updateSysSetting` / `updateSysSettingCallback` 通道，注册于 `ipc.js`）。

## 3. 数据模型

无表结构变更，不涉及 `easychat.sql`、不动 `Tables.js`（§5 L4「本地 db 迁移」逐一规避）。`user_setting.sysSetting` 为既有 JSON 列，新增键：

```json
{ "localFileFolder": "...", "notifySwitch": true }
```

存量行无该键 → 读取时缺省 `true`（旧语义 = 开），无需 ALTER / 数据迁移。

## 4. 安全设计

- 鉴权: 无新端点；`updateSysSetting` 复用既有 `@GlobalInterceptor` 会话上下文（`store.getUserId()` 定位行，不接受前端传 userId——越权写他人设置不可行）。
- 数据权限: 仅操作当前登录用户自己的 `user_setting` 行。
- 输入校验: `notifySwitch` 强制 `Boolean()` 归一，其余键原样保留（防止覆盖丢 `localFileFolder`）。
- 敏感信息: **无通知正文外显**——闪烁不携带消息内容，锁屏场景无隐私暴露面（原横幅方案该风险随撤销消除）。
- SQL 注入: 复用 Model 层参数绑定，无拼接。

## 5. ADR（架构决策记录）

### ADR-001: 闪烁发起层选主进程而非渲染进程

- 状态: 已接受
- 上下文: `flashFrame` 需要 `BrowserWindow` 句柄——主进程 `wsClient.js` 收帧处已持有 `getWindow("main")` 与失焦判断语境，零额外 IPC；渲染层拿不到窗口句柄（preload 零改动前提）。
- 决策: 闪烁在 `wsClient.js` 收帧处发起（`notification.js` 门禁辅助）。
- 后果: 正面——零 preload 改动（规避 L4）、零后端改动；负面——主进程模块增多一个，与 WS 接收轻度耦合（以独立模块 + 单函数调用隔离）。

### ADR-002: 开关持久化进 `sysSetting` JSON 而非新表/新列/Electron Store

- 状态: 已接受
- 上下文: 三条路——①新列：命中 §5「Tables.js 表结构变更」L4 + SQLite 迁移；②Electron Store：机器级而非用户级，多账号切换会串；③既有 `sysSetting` JSON：用户级、已有读写链路（`selectSettingInfo` / `updateSysSetting` / `getSysSetting` 通道）。
- 决策: 走 ③，新增键 `notifySwitch`，缺省 true。
- 后果: 正面——非 L4、零迁移、复用全链路；负面——JSON 内键无 schema 校验（以读取端 `Boolean()` 归一 + 缺省兜底缓解）；主进程内存缓存与 DB 存在极短不一致窗口（开关切换即刷新内存，可接受）。

### ADR-003: 需求修订决策——横幅撤销，改闪烁

- 状态: 已接受（2026-09-24 人工当场指令）
- 上下文: 原方案为系统横幅 + 声音 + 点击跳转；人工明确「不应是右下角横幅，参照微信做图标闪烁」。
- 决策: ①整体移除 Electron `Notification` API 调用、提示音与 `notificationClicked` 双端链路；②闪烁收敛为白名单触发 + 开关门禁（既有的全帧无条件闪烁属误闪，一并修正）；③点击交互交回系统原生（点闪烁中的任务栏按钮 = 激活窗口，微信同款，无会话跳转）。
- 后果: 正面——零内容外显隐私风险、代码更薄（无组装/无节流/无渲染层监听）、体验对齐微信；负面——用户须自行在会话列表定位未读（微信亦然），无法一键跳会话。

### ADR-004: 被否决的备选

- 后端推送通知/横幅: 通知是纯本机表现；横幅已被人工否决 —— 拒绝。
- 新增 preload API: 命中 §5「preload 暴露面」L4，且闪烁无需渲染层参与 —— 拒绝。
- 保留全帧闪烁仅加开关: 系统帧（ACK/SYNC/撤回等）闪烁属误闪，与「参照微信」目标不符 —— 拒绝，改为白名单收敛。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 托盘 `hide()` 后窗口无任务栏按钮，闪烁不可见 | 中 | 中 | 系统限制（无按钮可闪），非缺陷——记入 QA 已知边界；点托盘图标可恢复窗口。冒烟覆盖「最小化到任务栏」（可闪）与「托盘隐藏」（不闪属预期）两态 |
| 重连 SYNC 补推批量误闪 | 中 | 低 | 抑制规则 5（5 分钟时效）；闪烁本身无正文、瞬时即止 |
| 群自身回声误闪 | 中 | 中 | 既有 `sendUserId === 自己` 跳过分支 + 门禁内二次防御；调用点顺序硬约束写入 tasks 验收 |
| 移除顶部全帧 `flashFrame` 后某类消息漏闪 | 低 | 中 | 白名单显式列出 2/5/4；任务 1.4 走查确认无其他消息类帧依赖旧闪烁 |
| 开关关闭后仍闪 | 低 | 中 | 每次闪前读内存缓存；`updateSysSetting` 成功回调后立即刷新缓存 |
| `updateSysSetting` 覆盖丢既有键 | 低 | 高 | 读-改-写整份 JSON，仅归一 `notifySwitch`，禁止整键替换；QA 断言 `localFileFolder` 仍在 |

## 7. 依赖与前提

- 无 Change 依赖（`multi-device-sync` / `recall-sender-sync` 已归档，其 `extendData` 回填链路为既有前提）。
- 无部署/配置前提；不新增 npm 依赖（`flashFrame` 为 Electron 内置 `BrowserWindow` API）。
- 验证基建沿用既有 Change 口径：项目无单测基建，以 build + 冒烟 + 截图证据验收，不引入新测试依赖。
