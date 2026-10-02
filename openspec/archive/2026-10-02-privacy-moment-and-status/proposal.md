# Proposal — 朋友圈可见范围（用户级）+ 在线状态可见性 + 隐私设置统一页

- 创建日期: 2026-10-02
- 效率等级: L4

## Why

2②-A 补齐了「加我方式」与「黑名单」，隐私设置还剩两块硬缺口：

1. **朋友圈可见范围没有用户级设置**。单条动态级判定后端已完整实现（`MomentServiceImpl#canView` 覆盖 0–4），但**没有任何地方能配置「我默认想让谁看朋友圈」**——发每条都得在发布页手选，而发布页**只提供 3 个选项**（公开/仅好友/仅自己），白名单(3) 与黑名单(4) 根本选不了、也没法选人。
2. **在线状态无任何可见性开关**。`ChannelContextUtils#broadcastOnlineStatus` **无条件**向所有好友广播在线/忙碌/离线帧，用户没有任何手段隐藏自己的在线状态。

两处都是微信「隐私」页的标准项，属对标微信桌面版的核心缺口。

## What Changes

- 后端:
  - `user_info` **新增 4 列**（L4 数据结构）：`moment_visibility` / `moment_visible_list` / `moment_invisible_list` / `online_status_visible`
  - 新增 `POST /api/userInfo/updateMomentPrivacy`：更新朋友圈可见范围与自定义白/黑名单
  - 新增 `POST /api/userInfo/updateOnlineStatusVisible`：更新在线状态可见性
  - `ChannelContextUtils#broadcastOnlineStatus` 开头判断 `online_status_visible`，为 0 则不广播
  - **新增 WS 帧 `ONLINE_STATUS_HIDDEN(27)`**：关闭时立即向在线好友推帧抹除对方界面已有的在线状态
- 数据库: 产出 `easychat-migration-011-privacy-settings.sql`（幂等）+ 同步 `easychat.sql` 基线
- 前端:
  - **新建统一「隐私」页** `views/setting/Privacy.vue`：加我的方式 + 朋友圈可见范围 + 在线状态可见性 + 黑名单 四个区块（2②-A 的 ADR-002 明确把「是否合并」留到 2②-B 评估，现按微信信息架构合并）
  - 迁移：`UserInfo.vue` 朋友权限移入、`Blacklist.vue` 黑名单区块并入；`Setting.vue` 菜单改为「隐私」单入口
  - `PublishMoment.vue`：可见范围补齐 5 项（0–4），白/黑名单项可调 `UserSelect.vue` 选人；打开时以用户级设置为默认值
  - `wsClient.js` 新增 case 27：抹除该联系人的在线状态
- 数据库迁移: 4 列新增，`easychat.sql` 同步

## Capabilities

- C1: 用户可在统一「隐私」页配置**朋友圈可见范围**（公开 / 仅好友 / 仅自己 / 自定义白名单 / 黑名单）并维护自定义名单成员
- C2: 发布朋友圈时**以用户级设置为默认值**，且可单条临时调整（含白/黑名单选人）；单条选择不回写用户级设置
- C3: 在线状态可见性开关：关闭后不再向好友广播在线状态帧，且**立即推帧抹除**好友界面已有的状态；重新开启则立即广播当前状态
- C4: 四项隐私设置集中在同一个「隐私」页（对齐微信信息架构），单一真源、单一入口

## Impact

- 对外接口: **2 个新增端点**、0 个出参变更。**WS 协议新增 1 个帧类型 27**（既有 0–26 不变，旧客户端收到未知类型应忽略）
- 存量数据: `moment_visibility` 默认 **0（公开）**、`online_status_visible` 默认 **1（展示）**——即**存量用户行为不变**，不需要提醒任何人重新设置。白/黑名单列默认 NULL
- 性能: `broadcastOnlineStatus` 每次多一次 `user_info` 查询。已在 `ChannelContextUtils` 的 WS INIT 路径中批量加载用户信息处复用，避免热路径重复查库（见 design §1）
- 安全: **不放大任何权限**。两个端点均不接受 `userId`；名单成员只能是自己的好友（`UserSelect` 走既有 `loadContact`）
- 回退方案: DDL 加列**向后兼容**（有默认值），`git revert` 代码后旧版本不读这 4 列即可继续运行；列可保留不影响。DDL 本身亦幂等可重复执行

---

## ☑ 人工确认关卡

> 本提案经 **用户（项目 owner）** 于 **2026-10-02** 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 确认要点（两处显式选择）：
> 1. 信息架构 = **合并为统一「隐私」页**（四个区块），接受迁移 2②-A 已交付的 `UserInfo.vue` 朋友权限与 `Blacklist.vue` 的回归面
> 2. 在线状态隐藏 = **主动推帧立即抹除**（新增 WS 帧 27），而非仅停止广播
