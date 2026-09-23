# Retro — 单聊撤回实时同步到发送方自己的多台设备

- 日期: 2026-09-23
- 效率等级: L3

## 做得好

- 四件套先行、人工关卡通过后才动代码；design ADR-001 提前识别出 `applyContactConvert` 白名单含 type 14 的"contactId 转成自己"雷区，实现顺序（副本先取、转换后行）一次写对。
- 前端零改动结论先用代码走查证实（`wsClient` 跳过规则对 14 豁免）再写进 tasks，避免了无谓的前端改动范围。
- 冒烟脚本设计成三设备拓扑（发送方 x2 + 对端 x1），一次运行同时断言"发送方副本语义"与"对端原路径不受影响"两个方向；冒烟前后取数据库基线，残留可精确回滚。

## 问题

- 冒烟第一轮超时零输出：WS 连的是 `ws://host:5051/?token=`，而 Netty `WebSocketServerProtocolHandler` 只接受 `/ws` 路径，握手无响应导致 promise 卡死。
- 第二轮 sendMessage 返回 400/code=600：脚本按 `messageType=1` 发普通消息，但 `MessageTypeEnum.CHAT=2`（1 是打招呼）。
- 冒烟污染了用户正在运行的真机本地 SQLite 会话行（`-6`/`14` 帧把 `last_message` 改成冒烟内容），首次基线查询还猜错了 `chat_session_user` 列名（无 `last_message` 是 MySQL 侧，SQLite 侧反而有）。
- MySQL 与 SQLite 双库 schema 不一致（`chat_session` 单表 vs `chat_session_user` 行存储），恢复逻辑要分别构造。

## 原因

- 冒烟脚本的协议参数（WS 路径、messageType 枚举值）凭假设而非读代码得出：没有先查 `NettyWebSocketStarter` 的路径常量与 `MessageTypeEnum` 数值表。
- 环境里有"正在运行的真机客户端"这一活体副作用面，冒烟前只取了 MySQL 基线，没枚举真机接收 WS 帧后会写哪些本地表。

## 改进方案

- 写 WS/协议类冒烟脚本前，先机械读三处：Netty 握手路径常量、消息类型枚举表、端点 `@Valid` 参数约束；参数以读到的为准，不凭印象。
- 涉及 WS 广播的冒烟，副作用清单按"帧 -> 客户端 handler -> 本地落库"逐帧推演（14 帧会改会话行、-6 帧会改 lastMessage），双库（MySQL + 运行中真机的 local.db）一并取基线并一并回滚。
- 冒烟脚本给每步加显式超时与阶段日志（本次 ws_diag.js 补救有效），避免再次"零输出干等"。
