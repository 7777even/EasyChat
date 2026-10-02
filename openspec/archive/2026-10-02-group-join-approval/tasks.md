# Tasks — 群入群审批闭环

- 关联 Design: 2026-10-02-group-join-approval/design.md
- 创建日期: 2026-10-02
- 预估总工时: 11h

> 任务按实施顺序排列；单条 ≤2h。
> **[TDD]** 标记的任务必须先写失败测试再实现。

## 阶段一：审批可见性与审批权限（后端核心）

- [x] **[TDD]** 新增 `UserContactApplyServiceImplTest#dealWithApply_groupApply_adminCanApprove`：构造 `contactType=GROUP` 申请 + 操作人 role=ADMIN → 期望**通过**（实测红：Business exception，`receiveUserId` 强等导致 CODE_1001） — ≤1h
- [x] **[TDD]** 补 `dealWithApply_groupApply_memberRejected`（role=MEMBER → 期望 2305，实测红 got 1001）、`dealWithApply_groupApply_nonMemberRejected`（非群成员 → 期望 2304，实测红 got 1001）、`dealWithApply_friendApply_stillRequiresReceiver`（`contactType=USER` + 非 receiveUserId → 期望 1001，且不得走 `checkGroupRole`）；另补 ownerCanApprove / receiverCanApprove / applyNotFound / invalidStatus / concurrentDuplicate / blacklistDoesNotJoin 六条回归护栏（共 10 例，改造前 7 例即绿） — ≤1h
- [x] `UserContactApplyQuery` 新增 `currentUserId` 字段（含手写 getter/setter，PO 无 Lombok）+ `UserContactApplyMapper.xml` 在 `query_condition` 新增审批可见性 `<if>`：`receive_user_id = currentUserId OR (contact_type = 1 AND contact_id IN (SELECT contact_id FROM user_contact WHERE user_id = currentUserId AND role IN (0,1) AND status = 1))`；**同时移除 `queryContactInfo` 两个 LEFT JOIN 中冗余的 `a.receive_user_id = #{query.receiveUserId}` 守卫**并留注释论证其安全性（两侧均 PK 1:1 无扇出） — ≤1h
- [x] `UserContactApplyServiceImpl#dealWithApply` 审批人判定抽出 `checkApplyAuthority` 按 `contactType` 分流：`USER` 保持 `receiveUserId` 强等；`GROUP` 走 `checkGroupRole(我, contactId, ADMIN)`（群主 role=0 天然满足阈值，无需特判） — ≤1h
- [x] `UserContactController#loadApply` 与 `ChannelContextUtils`（WS INIT 申请红点）**两处**同时改用 `currentUserId` — ≤30min
- [x] **[TDD]** 转绿：`UserContactApplyServiceImplTest` 10/10 PASS；`mvn -B test` 140/140；`node scripts/verify/verify_mapper_params.mjs` exit 0 — ≤1h

## 阶段二：收编入群路径（后端核心）

- [x] **[TDD]** 新增 `GroupJoinApplyTest#joinByQrCode_delegatesToApplyAdd`：断言**不得**由 QR 服务自行 `addContact`、且 `applyAdd` 必被调用（实测红：`Wanted but not invoked ... zero interactions`）；另补 joinTypeZero/invalidToken/groupNotFound/alreadyMember/joinByInvite 五例 — ≤1h
- [x] `GroupQrCodeServiceImpl#joinByQrCode` 改为委托 `UserContactApplyService#applyAdd(tokenUserInfo, groupId, "GROUP", "通过群二维码申请加入")`，返回 `Integer joinType`；**保留原有 token 有效性 / 群存在性 / 已是成员三项校验**（不下沉到 `applyAdd`，并顺带删掉两处无用的 `UserContactQuery` 局部变量） — ≤1h
- [x] `GroupInviteServiceImpl#joinByInvite` 同上（附言「通过群邀请链接申请加入」）；同步 `GroupQrCodeService` / `GroupInviteService` 接口返回类型 `void` → `Integer` — ≤1h
- [x] `GroupController` 两个 join 端点出参 `Result<Void>` → `Result<Integer>`，透传 joinType — ≤30min
- [x] **[TDD]** 新增 `GroupJoinJoinTypeContractTest`（3 例）固化 joinType 出参契约；转绿 `GroupJoinApplyTest` 7/7；`mvn -B clean package -DskipTests` 0 error；`node scripts/check-api-contract.mjs --strict` exit 0（**确认出参类型变化未误判为漂移**） — ≤1h

## 阶段三：客户端入口

- [x] **[TDD]** `Api.js` 已有 `joinGroupQrCode` / `joinGroupInvite`；新增 `components/GroupJoinDialog.vue` 按 `joinType` 分流提示（`0` → 「已加入该群聊」；`1` → 「已提交入群申请，等待群主或管理员同意」）；`code=1001` 走统一错误提示、**对话框不关闭**、不误报已加入；空 token 前端拦截；显式 loading 态；界面明示审批规则 — ≤1h
- [x] `ContactApply.vue` 列表 `contactType=GROUP` 徽标由「群聊」改为「入群申请」，好友申请保持「好友」；`contactName` 由后端 SQL 保证为群名 — ≤30min
- [x] 新增 `GroupJoinDialog.vue`（两个 tab + token 输入 + 空值拦截 + 深色变量），eslint **0 problem** — ≤1h
- [x] `Contact.vue` 搜索框旁加「加入群聊」入口挂载对话框；`onGroupJoined` 在 `joinType=0` 时刷新 `loadMyGroup` + `loadContact('GROUP')`（定义放在两个函数之后以避开 TDZ）；补 `.join-group-btn` 样式与 hover — ≤30min
- [x] 渲染进程 `npm run build` 0 error（36.02s）；`node scripts/check-ipc-registration.mjs --strict` exit 0（本次不新增 IPC 通道，确认无回归） — ≤1h

## 阶段四：活体验证与文档同步

- [x] **[TDD]** 新增 `scripts/smoke/smoke_group_join_approval.py` 并跑通 **48/48 PASS，exit 0**：join_type=1 扫码不入群 + 落单 + 返回 1、重复扫码幂等、群主可见可审、已是成员/无效 token → 1001、join_type=0 返回 0 直接入群、邀请链接路径对称、普通成员不可见不可审（2305）、群管理员可见可审、好友申请负面护栏、临时数据自清理 — ≤2h
- [x] 活体冒烟：分页 `total` 与列表同源断言、申请附言来源标注断言、好友申请 `contact_id = receive_user_id` 语义修正后通过 — ≤1h
- [x] 同步 `docs/system-facts.md`（§12 新增入群审批事实 + 变更日志一行，含 `currentUserId` 与 JOIN 守卫移除的说明）与 `easychat.sql` 核对（**确认无 DDL 变更**） — ≤30min
- [x] 同步 `engineering/qa/2026-10-02-group-join-approval.md` + 冒烟输出快照 `2026-10-02-group-join-approval-smoke.txt` — ≤30min

## 阶段五：收尾

- [x] 同步 `engineering/retro/2026-10-02-group-join-approval.md`（四段式） — ≤30min
- [x] 落实 Retro 结论：`AGENTS.md` 新增 §2.1「验证的两条硬纪律」（门禁须实跑有判别力 / Windows 禁用 PowerShell 改源码）；**当场修正批次 1 引入的缺陷**——CI 的 `npx eslint .` 步骤在改动前即 exit 2，恒红，已移除并写明恢复条件 — ≤30min
- [x] spec-delta 回写 `openspec/specs/group-join-approval/spec.md`（新建 capability）+ 归档 Change — ≤30min

## DoD 自检（完成后逐项确认）

- [x] `openspec/archive/2026-10-02-group-join-approval/tasks.md` 全部勾选
- [x] `mvn -B test` 全绿 **140/140**（原 120 例零回归 + 新增 20 例）
- [x] `mvn -B clean package -DskipTests` 0 error
- [x] `node scripts/check-api-contract.mjs --strict` exit 0（2 个出参变更为已知且已确认）
- [x] `node scripts/verify/verify_mapper_params.mjs` exit 0（新增 XML 条件后重跑，27/27）
- [x] 权限闭环已验证：非群主非管理员的成员**无法**通过任何路径入群（扫码 / 邀请 / 搜索三条路径均验，其中搜索路径为既有 `applyAdd`，本次由 2.1/2.2 委托保证等价）
- [x] `loadApply` 分页 `total` 与列表同源（冒烟断言）
- [x] WS 申请红点与列表口径一致（两处调用点共用同一 XML 条件，代码同源已对账；实际红点变化待 GUI 验证，已在 QA §3 列为未运行项）
- [x] 无 DDL 变更，`easychat.sql` 与迁移脚本无需改动（确认非遗漏）
- [x] 归档闭环完成（spec-delta 回写 `specs/group-join-approval/` + 移入 `archive/`）
- [x] QA / Retro 记录已落 `engineering/`
