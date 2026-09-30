# Proposal — 拍一拍

- 创建日期: 2026-09-30
- 效率等级: L3

## Why

微信「拍一拍」是高频轻互动功能，当前 EasyChat 缺少好友间的轻量互动手段。补上后可提升社交活跃度，且实现成本低（复用现有消息链路）。

## What Changes

- 后端: MessageTypeEnum 新增 NUDGE(26)；UserContactController 新增 `/contact/nudge`；UserContactServiceImpl 新增 sendNudge 方法；ChannelContextUtils 白名单加入 NUDGE
- 前端: UserDetail.vue 更多菜单加入口；wsClient.js 新增 case 26；Chat.vue 新增系统消息渲染 + 窗口抖动；Setting.vue 新增后缀设置；UserSetting.js 支持 nudgeSuffix
- 数据库: 无表结构变更（复用 chat_message 表，messageType=26）

## Capabilities

- C1: 用户可在好友详情页向好友发送拍一拍消息
- C2: 拍一拍消息以系统消息样式显示在聊天记录中
- C3: 对方在线且正在聊天时收到窗口抖动动画提示
- C4: 用户可自定义拍一拍后缀，跨端同步

## Impact

- 对外接口: 新增 `POST /api/contact/nudge`，需同步前端 Api.js
- 存量数据: 无影响（新消息类型，不涉及迁移）
- 性能 / 安全: 无性能影响；需校验好友关系防止骚扰
- 回退方案: 删除新增枚举值 + 接口 + 前端入口即可回滚

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 2026-09-30 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
