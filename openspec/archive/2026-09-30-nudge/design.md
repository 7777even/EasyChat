# Design — 拍一拍

- 关联 Proposal: 2026-09-30-nudge/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

```
用户A点击拍一拍 → POST /contact/nudge → 后端校验好友关系
    → 构建 MessageSendDto(messageType=26) → ChatMessageService.sendMsg()
    → 落库 + 推 WS → 用户B收到帧 → 渲染进程显示系统消息 + 窗口抖动
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| MessageTypeEnum | 新增 NUDGE(26, "%s拍了拍你", "拍一拍") | 枚举定义 |
| UserContactController | 新增 POST /contact/nudge | 路由 + @Valid + 调 Service |
| UserContactService | 新增 sendNudge(userId, contactId) | 接口定义 |
| UserContactServiceImpl | 校验好友关系 + 构建消息 + 发送 | 业务规则 + 事务边界 |
| ChannelContextUtils | NUDGE 加入 CONTACT_CONVERT_TYPES | 联系人语义转换 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Api.js | 新增 nudge: "/contact/nudge" | API 路径常量 |
| UserDetail.vue | 更多菜单新增「拍一拍」 | 用户交互入口 |
| wsClient.js | 新增 case 26 处理 | WS 帧接收与转发 |
| Chat.vue | 拍一拍消息渲染 + 窗口抖动 | 渲染进程业务逻辑 |
| ChatMessageSys.vue | 支持拍一拍消息样式 | 系统消息展示 |
| Setting.vue | 新增拍一拍后缀设置 | 用户设置页面 |
| UserSetting.js | 支持 nudgeSuffix 字段 | 本地设置读写 |

## 2. 接口设计

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/contact/nudge` | POST | `{ contactId: string }` | `Result<Void>` | 登录用户 |

### 错误码

复用现有错误码：
- `2401` 非好友关系（校验失败）
- `1001` 参数非法（contactId 为空）

## 3. 数据模型

无表结构变更。拍一拍消息存入 `chat_message` 表，`message_type=26`。

自定义后缀存储在 `user_setting.sysSetting.nudgeSuffix`（JSON 键），跨端同步经 `-7` 帧。

## 4. 安全设计

- 鉴权: `@GlobalInterceptor` 登录校验
- 数据权限: 校验好友关系（user_contact 表存在记录且 status 正常）
- 输入校验: `@NotEmpty contactId`
- SQL 注入防护: MyBatis `#{}` 参数绑定

## 5. ADR

### ADR-001: 拍一拍消息存储方式

- 状态: 已接受
- 上下文: 拍一拍是否需要落库？
- 决策: 落库，作为系统消息类型（messageType=26）存入 chat_message
- 后果: 对方不在线也能在聊天记录中看到；与微信行为一致

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 恶意频繁拍一拍骚扰 | 中 | 中 | 后续可加频率限制（当前不做） |
| 窗口抖动影响用户体验 | 低 | 低 | 仅在正在聊天时触发，3 次后停止 |

## 7. 依赖与前提

- 无前置依赖
- 需确认 ChatMessageSys.vue 是否支持动态消息类型
