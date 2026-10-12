# Design — @ 提及 ID 形态收紧

## 架构

改动落在两处，互不依赖：

```
【发送侧·客户端】                              【管理侧·后端】
MessageSend.vue                                AdminUserInfoBeautyController
  └─ atMentionCore.extractAtUserIds()           └─ UserInfoBeautyServiceImpl.saveAccount()
       └─ AT_USER_ID_PATTERN  ← 本次收紧             └─ 靓号 userId 字符集校验  ← 本次新增
              │
              v
       extraData.atUserIds  /  MessageSendDto.atUserIds
              │
              v
       chat_message.at_user_ids（仅写；服务端仅用作 like %id% 搜索过滤）
```

**关键事实（决定了方案形状）**：

1. `atUserIds` 在本仓**只写不读** —— 服务端不解析正文、无通知逻辑；前端不读收到的消息。
   故收紧正则**不会改变任何可观测行为**，只让落库值更干净。
2. 正则字符集的上界由 **userId 的实际取值集合**决定，而非由「看起来像什么」决定。
   该集合经活库与常量双向确认为：**`U` + 11 位数字**（`StringTools.getUserId()`）
   ∪ **`Urobot`**（`Constants.ROBOT_UID`，真实系统账号）。
3. 靓号是唯一能把任意字符写进 userId 的入口，且**当前零校验** —— 必须先堵住，
   否则正则收紧会把「管理员误建字母靓号」的用户彻底挡在 @ 之外。

## 决策（ADR）

### ADR-001: 正则收紧为 `@(U\d+|Urobot)(?![\w])`，而非 `@(U\d+)`

- 备选 A：`/@(U\d+)/g` —— **否决**。会漏掉系统账号 `Urobot`：
  它有头像文件（`AdminSettingController:38`）、有以它为 `contactId` 的会话
  （`ChatMessageServiceImpl:302`）。虽然 `atUserIds` 只写不读、丢掉它当前无后果，
  但这会让「@ 机器人」在将来有人基于 `atUserIds` 实现提醒时**静默失效**——
  属"靠死字段侥幸正确"，不可接受。
- 备选 B：`/@(U[A-Za-z0-9]+)/g` 维持现状 —— 否决，即本次要修的缺陷本身。
- 选定：`@(U\d+|Urobot)(?![\w])`。**该式精确等于真实 userId 集合**，可自我解释、可被门禁断言。

### ADR-002: 不加左词边界

`价格5@U10 美元` 仍会命中。加 `(?![\w])` 之类的**左**边界会误伤合法场景：
用户在输入框里**选中一段文本再点 @ 按钮**，`spliceAtText` 会产出 `文本@U001 `，
左边界会让这条合法提及被漏掉。

**取舍**：宁可保留一个无后果的假阳性（该字段只写不读），也不引入会漏掉真实提及的假阴性。

### ADR-003: 靓号校验只允许纯数字

与 `StringTools.getUserId()` = `"U" + getRandomNumber(11)` 严格对齐。
若放开为 `[A-Za-z0-9]+` 则字符集仍无上界、正则仍不敢收紧，**收口失败**。

### ADR-004: 校验放在 Service 而非 Controller

`AdminUserInfoBeautyController#saveBeautAccount` 直接收 `UserInfoBeauty` **PO**（无 `@Valid`，
本身就违反 AGENTS §3.6「不复用 Entity 做出入参」），但把 PO 改造成 DTO 属**无关重构**。
校验放 Service 可覆盖全部调用点，且不扩大本次改动面。

## 风险

| 风险 | 评估 |
|---|---|
| 收紧后漏掉存量字母靓号用户 | **已实测排除**：靓号表 0 行、非 `U+数字` 的 user_id 0 条 |
| 收紧后漏掉 `@Urobot` | ADR-001 已处理 |
| 管理员误建字母靓号 | 被新校验拦截，给出 `CODE_1001` |
| 已落库的幻影 `at_user_ids` | 活库该列无数据；即便有也只影响搜索命中，不做回溯清理（写进台账说明） |

## 依赖

无新增依赖。前端纯核心 `atMentionCore.mjs` 已可被 `verify_at_mention_core.mjs` 与 vitest 直接断言。

## 数据影响

**零**。无表结构变更、无字段变更、无数据迁移。