# Tasks — 拍一拍

- 关联 Design: 2026-09-30-nudge/design.md
- 创建日期: 2026-09-30
- 预估总工时: 3h

## 阶段一：后端基础结构

- [x] MessageTypeEnum 新增 NUDGE(26) — ≤15min
- [x] UserContactController 新增 POST /contact/nudge — ≤15min
- [x] UserContactService 新增 sendNudge 接口 — ≤10min
- [x] UserContactServiceImpl 实现 sendNudge（校验好友 + 构建消息 + 发送） — ≤30min
- [x] ChannelContextUtils 白名单加入 NUDGE — ≤5min
- [x] 后端 mvn compile 通过 — ≤15min

## 阶段二：前端适配

- [x] Api.js 新增 nudge 路径 — ≤5min
- [x] UserDetail.vue 更多菜单加入口 — ≤15min
- [x] wsClient.js 新增 case 26 处理 — ≤15min
- [x] Chat.vue 拍一拍消息渲染 + 窗口抖动逻辑 — ≤30min
- [x] ChatMessageSys.vue 支持拍一拍样式 — ≤15min
- [x] Setting.vue 新增拍一拍后缀设置 — ≤20min
- [x] UserSetting.js 支持 nudgeSuffix — ≤10min

## 阶段三：验证与同步

- [ ] 后端启动冒烟：拍一拍发送 + 接收 — ≤30min
- [x] 前端构建通过 — ≤15min
- [ ] 同步 engineering/qa/ 验证报告 — ≤15min

## 阶段四：收尾

- [ ] 同步 engineering/retro/ 复盘记录 — ≤15min
- [ ] spec-delta 回写 openspec/specs/nudge/spec.md + 归档 Change — ≤15min

## DoD 自检（完成后逐项确认）

- [ ] tasks.md 全部勾选
- [x] mvn compile 0 error
- [x] 前端构建通过
- [ ] 归档闭环完成
- [ ] QA / Retro 记录已落 engineering/
