# Proposal — 加我方式可配置 + 黑名单可查可解（零 DDL）

- 创建日期: 2026-10-02
- 效率等级: L3

## Why

两处「只能进不能出」的半成品设置：

1. **加我方式**（`user_info.join_type`）—— `applyAdd` 会读它、`UserInfoVO` 会出它、`UserInfo.vue` 会显示它，但**没有任何更新接口**（`UserUpdateDTO` 只有 nickName/sex/签名/头像）。用户看得到自己的设置却改不了。
2. **黑名单** —— `POST /contact/addContact2BlackList` 能加，但**没有列表、没有解除**。`UserDetail.vue` 里点「加入黑名单」后即**永久无法退出**，误点一次就只能清库。

这两项是「功能对标微信桌面版」的最低门槛，且都是零 DDL。

## What Changes

- 后端:
  - 新增 `POST /api/userInfo/updateJoinType`：更新 `user_info.join_type`（0 直接加入 / 1 加我时需验证）
  - 新增 `POST /api/contact/loadBlackList`：查我拉黑的用户列表（含对方昵称，按拉黑时间倒序）
  - 新增 `POST /api/contact/removeBlackList`：解除黑名单，**删除双向关系行**
  - `UserContactService` 新增 `removeBlackList`；复用既有 `UserContactMapper#deleteByUserIdAndContactId`
- 前端:
  - `UserInfo.vue` 的「朋友权限」由**只读文本改为可编辑单选**（原地改，不搬动，避免两处真源）
  - 新增 `views/setting/Blacklist.vue` 黑名单管理页（列表 + 解除 + 空态）
  - `Setting.vue` 菜单加「黑名单」项 + 新增路由 `/setting/blacklist`
  - `Api.js` 补 3 个端点
- 数据库: **无表结构/字段变更**，不需迁移脚本

## Capabilities

- C1: 加我方式可由用户在「账号设置 → 朋友权限」自助修改，保存后**立即对新的好友申请生效**（`applyAdd` 直读 DB，无缓存）
- C2: 黑名单列表可查，展示对方头像/昵称与拉黑时间，按最近拉黑倒序
- C3: 黑名单可解除，解除后对方不再处于「被拉黑」状态，且对方可重新向我发起好友申请

## Impact

- 对外接口: **3 个新增**（`/userInfo/updateJoinType`、`/contact/loadBlackList`、`/contact/removeBlackList`），**0 个出参变更、0 个语义变更**。`check-api-contract --strict` 需保持 exit 0
- 存量数据: 无影响。`user_info.join_type` 既有值原样生效；既有 `user_contact` 拉黑行（status=4）将首次出现在列表中
- 性能: 黑名单查询复用既有 `findListByParam` + `idx(user_id, contact_type)`，量级为个位数，无分页必要（与既有 `/contact/loadContact` 范式一致）
- 安全: **不放大任何权限**。`updateJoinType` 只改自己；`loadBlackList` 只查自己；`removeBlackList` 只删自己发起的拉黑行。**关键约束**：`status=5 BLACKLIST_BE`（我被别人拉黑）**不在**我的黑名单内，不可见也不可解——否则我能单方解除别人的拉黑
- 回退方案: 改动为纯新增接口 + 一个 Vue 页，无既有接口签名变更，`git revert` 单个提交即可。无数据面影响、无迁移

---

## ☑ 人工确认关卡

> 本提案经 **用户（项目 owner）** 于 **2026-10-02** 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 确认要点（两处显式选择）：
> 1. 拆分 = **拆两个 Change，本 Change 只做零 DDL 的 2②-A**；朋友圈可见范围 + 在线状态可见性（含 DDL）另立 2②-B
> 2. 解除黑名单语义 = **删除关系行**（而非改回好友状态）
