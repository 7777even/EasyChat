# Retro — 语音/视频通话

- 关联 Change: `openspec/changes/2026-09-26-voice-call`
- 日期: 2026-09-26

## 做得好

1. **后端前置于前端、契约先行**：后端信令中继 + 房间 + `call_log` 落库 + TURN 注入在前一轮已落地并经 openspec 四件套确认，本轮只补前端，避免「前后端一起改、互相猜字段」的反复。
2. **复用约束守得严**：全程只扩展现有 Netty WS 帧（-10~-17），不新增端口 / HTTP 接口 / 运行时依赖；媒体 P2P 不经服务器；TURN 凭据只由服务端下发、前端源码零硬编码。这把「安全红线（媒体/信令分离）」落到了实现里，而非文档里。
3. **状态机与信令解耦**：`useCallStore` 只负责帧分发与状态，`WebRTC.js` 只负责 `RTCPeerConnection` 生命周期，主进程只做收发转发——三层各管一摊，调试时可直接在 `handleFrame` 单点推理。
4. **门禁当成真门禁**：本轮前端改动在提交前实跑 `electron-vite build` + `check-api-contract` + `check-openspec-hygiene` + `check-ipc-registration --strict` 四项，全绿后才收尾，没靠「看起来能编译」拍脑袋。

## 问题

1. **发起方状态机卡在 calling**：`handleFrame` 在收到 `CALL_JOIN` 且自己不是新成员时，没有把 `calling` 转成 `connected`，UI 会停在「呼叫中」而实际已接通。靠代码走查（而非构建/门禁）发现——这类逻辑缺陷静态门禁抓不到。
2. **tasks 计划与实际结构不符**：计划写了 `GroupChat.vue`，但项目里群聊就是 `Chat.vue`（按 `contactType` 分支），不存在独立群聊视图文件。属于「计划没先对齐现有代码结构」。
3. **`-element-plus/icons-vue` 此前只是传递依赖**：前端直接 import 了它，却没在 `package.json` 显式声明，存在「`npm install` 后被摇树/未解析」的隐患。

## 原因

1. `CALL_JOIN` 的语义是「房间成员广播」，发起方收到时既不是新成员也不是被叫，原代码只处理了「我是新成员」分支，漏了「我是发起方、别人刚加入」这一常见路径。
2. 写 tasks 时按「单聊/群聊两个视图」的通用假设，没先 `ls` 确认本项目群聊视图的真实文件名。
3. 图标按钮图省事直接用了 element-plus 自带的 `@element-plus/icons-vue`，因为 element-plus 依赖它、node_modules 里有，就想当然能 import，没意识到传递依赖不等于直接依赖。

## 改进方案

1. **群呼/多端状态流转补一组可断言的纯逻辑测试**：把 `useCallStore` 的 `handleFrame` 抽成不依赖 DOM 的纯函数（或桩掉 `WebRTC`），用脚本跑「A 发起 → B 接听 → A 收到 JOIN 转 connected」「C 中途加入 → A/B 收到 JOIN 并互发 offer」等场景，让状态机缺陷可自动回归。
2. **改代码/写计划前先对齐现有结构**：涉及视图/路由文件时先 `ls` 或 grep 确认真实文件名，不要把「通用假设」写进 tasks。
3. **直接 import 的三方包一律显式进 `package.json` dependencies**：把「传递依赖当直接依赖用」列为红线；本次已补 `@element-plus/icons-vue`。
4. **真实媒体链路留手动验证清单**：在 QA 里固化「本机双实例互打单聊 + 群呼 + TURN 对称 NAT」的手动验收步骤，作为发版前的必跑项（沙箱无法替代）。
