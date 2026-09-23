# QA — 单聊撤回实时同步到发送方自己的多台设备

- 日期: 2026-09-23
- 效率等级: L3
- 范围: `easychat-java` `ChannelContextUtils.send2User` / 新增 `sendRecallToSenderDevices`（单聊 USER 分发分支）；Service / Controller / Entity / SQL 零改动；前端零改动（走查 `wsClient.js` / `Chat.vue`）。不动 Redis topic、不改 `MessageSendDto` 包络。

## 验收口径

- tasks 1.1：撤回副本在 `applyContactConvert` **之前**取、仅 `RECALL(14)` 触发、仅单聊分支、空组判空。
- spec C1 / spec-delta「跨端消息撤回同步」：A 撤回 -> A 的其他在线设备实时更新；副本帧 `contactId` 保持会话对方（≠ sendUserId），不产生以自己为联系人的脏会话；群聊路径不产生重复帧；发送方无在线设备时判空跳过、DB 历史兜底。
- 根 AGENTS §2 矩阵：单 Service/WS 局部修改 -> `mvn compile`；对外行为变化 -> 接口链路冒烟。
- 根 AGENTS §3：接口行为变化但无新增端点 / 错误码；撤回复用既有权限校验（仅发送者本人）。

## 实际执行命令与结果

- `mvn compile -q` -> exit=0（通过 1/1）。
- `node recall_smoke.js`（三设备：U29953535216 x2 + U04259455805 x1；HTTP 发消息 -> 立即撤回 -> WS 收帧断言）-> **PASS=true，3/3 断言通过**：
  - A1/A2（发送方其他设备）14 帧 `contactId=U04259455805`（未转换，≠ sendUserId）。
  - B1（对端）14 帧 `contactId=U29953535216`（== sendUserId，转换后），原投递不受影响。
  - 撤回接口 HTTP 200 / code=0。
- 后端日志断言：`15:29:54 [INFO][ChannelContextUtils][sendRecallToSenderDevices][240] 撤回帧发送方副本已投递 sendUserId=U29953535216, devices=3`（含用户运行中的真机，与"广播给 U 的所有在线设备"一致）。
- 数据基线回滚（冒烟残留清理，验证 6/6）：MySQL DELETE 1827 后 `SELECT >=1827` 空；`chat_session` 与本地 `chat_session_user` 均恢复 `last_message='1'` / `1790145608366`；本地 `chat_message` 无 1827 幽灵行；基线行数 10 / MAX=1826 不变。
- 1.4 权限走查（记录不修改）：`recallMessage` L381 仅发送者本人、L386 类型白名单、L393 2 分钟窗口，均原样存在。
- 1.5 前端零改动走查：`wsClient.js` L193 跳过规则对 `messageType != 14` 才拦截（自发撤回帧放行）、L231 case 14 按 messageId 幂等更新不新增、`Chat.vue` L310/L734 处理 14 —— 零改动结论成立。

## 未运行项

- 前端 ESLint / Vite build：前端零改动，未跑。
- 真实双端 UI 人工目视：以三设备帧断言 + 后端日志替代，未做人工目视。
- 群聊撤回链路实跑：`sendRecallToSenderDevices` 仅挂 `send2User`（USER 分支），群路径代码零改动，未单独实跑；群场景依赖既有 `sendMsg2Group`，撤回者作为群成员本就在群 ChannelGroup 内（走查确认）。
- `mvn package -DskipTests`：本变更非依赖/构建/配置改动，按 §2 取 `mvn compile` 行，未跑 package。

## 证据附件

- `2026-09-23-recall-sender-sync.evidence.txt`（冒烟终端输出 + 后端日志行 + 回滚验证快照，随本报告同目录）。

## 结论

**通过**。验收口径逐条达成：3/3 帧断言 PASS、`mvn compile` 0 error、权限/前端走查符合预期、冒烟数据已回滚至基线。
