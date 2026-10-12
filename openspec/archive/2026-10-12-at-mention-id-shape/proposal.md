# Proposal — 收紧 @ 提及的 ID 形态：先约束靓号字符集，再把正则精确到真实 userId 集合

- 创建日期: 2026-10-12
- 效率等级: L3（业务规则变更：@ 提及的判定口径 + 靓号的可取值范围）

## Why

客户端用 `/@(U[A-Za-z0-9]+)/g` 从正文提取被 @ 的用户 ID，手打 `@Ubuntu` 会被提取为 `Ubuntu`、
`价格5@U10 美元` 亦命中（`@` 无左词边界），即**普通英文单词会被误判为提及**。

**但此前登记的两条理由均已在 2026-10-12 取证中被推翻**，故严重性需重判：

| 原登记 | 取证结论 |
|---|---|
| 「服务端解析正文、`atUserIds` 与之同形，是跨端契约」 | **不成立**。服务端只做 `ExtraDataTools.isAtAll(extraData)`（读 `atAll` **布尔标记**，与正文无关）；`atUserIds` 落到 `chat_message.at_user_ids` 后**仅作搜索过滤 `like %id%`，无任何解析** |
| 「误提取会让恰好存在该 id 的用户收到红点提醒」 | **不成立**。全仓检索无按 `atUserIds` 发通知/红点的代码，该字段**只写不读**（`MessageSend.vue:480` 注释所称「服务端用于红点提醒」为不实描述） |

**故实际影响为低**：幻影 id 只写进一个仅用于搜索的列，无通知、无误投、无渲染差异
（`ChatMessage.vue` 只着色 `@所有人`，从不解析 `@Ux`）。

**真正的问题在根因**：靓号 `UserInfoBeautyServiceImpl.saveAccount` 对 `beauty.getUserId()`
**零格式校验**（只查唯一性与邮箱），`/admin/saveBeautAccount` 更是直接收 PO 连 `@Valid` 都没有
→ 管理员可建出 `UABC`。**userId 字符集因此无上界**，这才是正则不敢收紧的原因。

## What Changes

- 后端: `UserInfoBeautyServiceImpl#saveAccount` 增加靓号字符集校验（须为纯数字，
  与 `StringTools.getUserId()` 产出的 `U` + 11 位数字对齐），违规抛 `CODE_1001`。
  **不改接口签名、不改表结构**。
- 前端: `atMentionCore.mjs` 的 `AT_USER_ID_PATTERN` 由 `@(U[A-Za-z0-9]+)` 收紧为
  **`@(U\d+|Urobot)(?![\w])`**。
  - 之所以保留 `Urobot`：`Constants.ROBOT_UID = "U" + "robot"` 是**真实系统账号**
    （有头像文件、`ChatMessageServiceImpl:302` 以 `contactId == Urobot` 判会话），
    纯 `\d+` 会把它挡在 @ 之外。
  - 之所以**不加左词边界**：选了文本再点 @ 会产出「文本@U001」，加边界会漏掉合法提及。
    代价是 `价格5@U10` 这类仍会命中，但该字段只写不读，无可观测后果。
- 注释订正: `atMentionCore.mjs` 现注释声称「跨端契约」且误记 `@Ubuntu → buntu`（实为 `Ubuntu`），
  一并订正；`MessageSend.vue:346/480` 的不实描述同步订正。
- 数据库: **无表结构/字段变更**。

## Capabilities

- C1: 靓号只能取纯数字，与系统自动生成的 userId 形态一致（从源头收口字符集）
- C2: @ 提及的 ID 形态精确等于真实 userId 集合（`U`+数字 或 `Urobot`），
  普通英文单词（`@Ubuntu`/`@User`）不再被误判为提及

## Impact

- 对外接口: `/admin/saveBeautAccount` **签名与响应不变**；仅在靓号含非数字时新增 `CODE_1001` 拒绝。
  前端调用方无需改动（错误走既有 Toast 分支）。
- 存量数据: **经活库实测为零影响** —— `user_info` 4/4 行均为 `U`+11 位数字；
  非 `U+数字` 的 user_id **0 条**；靓号表 `user_info_beauty` **0 行**；
  `chat_message.at_user_ids` 无数据。**无需数据迁移。**
- 性能 / 安全: 无性能影响。安全性略有提升——admin 侧不再能写入任意字符集的 userId。
- 回退方案: 正则与校验各自独立，可单独回退；回退正则不影响任何已落库数据。

---

## ☑ 人工确认关卡

> 本提案经 **用户（需求方）** 于 **2026-10-12** 确认，允许进入 design 阶段。
> 原始选项：「配套修：先约束靓号再收紧正则（推荐）」——即本文件 What Changes 所述方案。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> ⚠ 立项过程中推翻了两条既有判断，据此**同步订正** `docs/system-facts.md` §14 遗留 #19
> （已单独提交 `2c1a304`）：① `@Ubuntu` 实提取为 `Ubuntu` 而非 `buntu`；
> ② 「服务端消费同一形态」与「红点提醒」两条理由均不成立，严重性由「中」下调为「低」。