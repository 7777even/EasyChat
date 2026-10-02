# Proposal — 群入群审批闭环（修复二维码/邀请绕过 join_type + 管理员可审批 + 补前端入口）

- 创建日期: 2026-10-02
- 效率等级: L4

## Why

群主通过 `group_info.join_type` 设置了「需管理员同意后加入」，但**群二维码与邀请链接两条入群路径完全绕过了这个字段**——对方扫个码或点个链接就直接进群了，权限设置形同虚设。这是能真实绕过权限的洞，且与「功能对标微信桌面 IM」的核心群治理能力直接冲突。

同时排查发现两个连带缺口：群管理员**无权审批**入群申请（`dealWithApply` 硬性要求 `userId == receiveUserId == 群主`），且**看不到**入群申请列表（`loadApply` 只按 `receiveUserId=我` 过滤）；此外扫码/邀请入群**完全没有前端入口**，被邀请者无路可走。

## What Changes

- 后端:
  - `GroupQrCodeServiceImpl#joinByQrCode` / `GroupInviteServiceImpl#joinByInvite` 由「直接 `addContact` 入群」改为**委托 `UserContactApplyService#applyAdd(contactType=GROUP)`**，复用既有 `join_type` 语义（0 直接加入 / 1 落申请单 + 推 `CONTACT_APPLY` 帧）
  - 两个 join 接口出参 `Result<Void>` → `Result<Integer>`，返回 `joinType` 供前端区分「已加入」/「已提交申请待审批」
  - `UserContactApplyServiceImpl#dealWithApply` 群入群申请的审批人由「仅群主」放宽为「群主或任一群管理员」（复用 `GroupInfoService#checkGroupRole(ADMIN)`）
  - `UserContactController#loadApply` 查询条件由「`receiveUserId=我`」扩展为「`receiveUserId=我` OR（`contactType=GROUP` 且我在该群 role=ADMIN）」
- 前端:
  - **新增**「加入群聊」入口（`Contact.vue` 搜索框旁 + 新对话框组件），支持输入群二维码 token / 邀请链接 token 加入，按 `joinType` 给出不同提示
  - `ContactApply.vue` 列表对 `contactType=GROUP` 的申请标注「入群申请」，与好友申请区分
- 数据库: **无表结构/字段变更**——复用既有 `user_contact_apply` / `group_info.join_type`，不需迁移脚本

## Capabilities

- C1: 群二维码与邀请链接入群**统一受 `group_info.join_type` 管辖**，`join_type=1` 时落 `user_contact_apply` 申请单并推 `CONTACT_APPLY(4)` 帧给群主，不再直接入群
- C2: 两个 join 接口返回 `joinType`（`0`=已直接加入，`1`=已提交申请待审批），客户端可区分提示
- C3: 群入群申请的**审批人是群主或任一群管理员**；且群管理员能在申请列表中**看到**本群的入群申请
- C4: 客户端提供「扫码入群 / 邀请链接入群」入口，无需再走「搜索群名→申请」的唯一路径

## Impact

- 对外接口: **2 个出参变更**（`POST /api/group/qrCode/join`、`POST /api/group/invite/join`：`Result<Void>` → `Result<Integer>`）。前端 `Api.js` 已定义这两个端点但**无任何调用方**（本变更新增首个调用方），故对存量调用方零破坏。`check-api-contract --strict` 需保持 exit 0
- 存量数据: 无影响。存量 `user_contact_apply` 行的 `receive_user_id` 已是群主，新增管理员可见性靠 `contact_type=GROUP` 关联，不改历史行
- 性能: `loadApply` 需额外查一次「我作为管理员的群列表」做内存过滤，群数量级下可忽略；不引入 JOIN
- 安全: **正向**——权限绕过被堵；**权限面放大**——群管理员获得入群审批权（属预期业务语义，微信同款）
- 回退方案: 后端改动集中在 4 个 Service/Controller 方法，`git revert` 单个提交即可完全回退；前端入口为纯新增，回退后二维码生成侧仍可用（只是没有入群入口）。无数据面影响、无迁移

---

## ☑ 人工确认关卡

> 本提案经 **用户（项目 owner）** 于 **2026-10-02** 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 确认要点（三处显式选择）：
> 1. 修复范围 = **连群管理员审批一起修**（不拆成两个 Change）
> 2. join 接口出参 = **改为 `Result<Integer>` 返回 `joinType`**，不靠异常表达
> 3. 明确**不做**「二维码临时免审批开关」——保持 `join_type` 单一真源
