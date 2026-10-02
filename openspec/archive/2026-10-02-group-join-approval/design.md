# Design — 群入群审批闭环

- 关联 Proposal: 2026-10-02-group-join-approval/proposal.md
- 创建日期: 2026-10-02

## 1. 架构设计

核心思路：**不新建审批机制，把两条"野生"入群路径收编到既有的 `applyAdd` 链路上**。`UserContactApplyServiceImpl#applyAdd` 已经完整实现了 `join_type` 分流（0 直接 `addContact` / 1 落 `user_contact_apply` + 推 `CONTACT_APPLY` 帧），问题只在于 QR/Invite 两条路没走它。

```
入群请求（三条路径，收编为一条真源）
  搜索群名 → 申请加入          SearchAdd.vue ──▶ POST /contact/applyAdd ──┐
  群二维码  → 扫码加入(新入口) GroupJoinDialog ──▶ POST /group/qrCode/join ─┤
  邀请链接  → 加入(新入口)     GroupJoinDialog ──▶ POST /group/invite/join ─┤
                                                                      ▼
                                        UserContactApplyService#applyAdd(contactType=GROUP)
                                                                      │
                        ┌─────────────────────┴─────────────────────┐
                   join_type=0                               join_type=1
                 （直接加入）                                 （需审批）
                        │                                        │
              addContact 直接入群                    落 user_contact_apply
                        │                            + 推 CONTACT_APPLY(4) 帧给群主
                        ▼                                        │
                   已入群 ◄──── 管理员/群主 dealWithApply(PASS) ──┘

审批可见性（本次新增，写在 Mapper XML 条件里，loadApply 与 WS 红点共用）
  UserContactApplyQuery.newField = currentUserId
  WHERE (
      receive_user_id = #{query.currentUserId}
      OR (contact_type = 1 AND contact_id IN (
            SELECT contact_id FROM user_contact
            WHERE user_id = #{query.currentUserId}
              AND role IN (0,1) AND status = 1     -- 0 群主 1 管理员
      ))
  )

审批权限（本次放宽）
  /contact/dealWithApply →
      contactType=USER  : 仍要求 receive_user_id == 我（好友申请语义不变）
      contactType=GROUP : checkGroupRole(我, groupId, ADMIN) 通过即可
                          （群主 role=OWNER 亦满足 ADMIN 判定，无需特判）
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `GroupQrCodeServiceImpl` | `joinByQrCode` 改为委托 `applyAdd`，返回 `Integer joinType` | 只做 token 反查 + 群存在性/成员校验，**入群语义交出** |
| `GroupInviteServiceImpl` | `joinByInvite` 同上 | 同上 |
| `GroupQrCodeService` / `GroupInviteService` | 接口返回类型 `void` → `Integer` | — |
| `GroupController` | 两个 join 端点 `Result<Void>` → `Result<Integer>` | 路由 + `@Valid` + 调 Service，不含业务判断 |
| `UserContactApplyServiceImpl` | `dealWithApply` 审批人判定按 `contactType` 分流 | 业务规则 + 事务边界，不感知 HttpServletRequest |
| `UserContactController` | `loadApply` 改用新查询字段 `currentUserId` | 只组装 Query，不含业务判定 |
| `ChannelContextUtils` | WS INIT 的申请红点 `selectCount` 同步改用 `currentUserId` | 与 `loadApply` 共用同一 XML 条件，保证红点与列表一致 |
| `UserContactApplyQuery` | 新增 `currentUserId` 字段 | 1:1 承载查询条件，非持久化对象 |
| `UserContactApplyMapper.xml` | `query_condition` 新增审批可见性 `<if>`；**移除 `queryContactInfo` 两个 LEFT JOIN 里冗余的 `a.receive_user_id = #{query.receiveUserId}` 守卫** | 只写 SQL，判定语义在 Service |

**依赖方向检查**：`GroupQrCodeServiceImpl` / `GroupInviteServiceImpl` → `UserContactApplyService`（新注入）。`UserContactApplyServiceImpl` 已注入 `UserContactService` / `UserContactMapper` / `GroupInfoMapper` / `UserInfoMapper`，**不反向依赖** Group 二维码/邀请 Service，无循环依赖。

`dealWithApply` 需要 `GroupInfoService#checkGroupRole`，而 `GroupInfoServiceImpl` 依赖 `UserContactService`（非 `UserContactApplyService`），方向安全。

### 已核查的实现细节（写 design 时先验证，避免基于假设施工）

1. **`checkGroupRole(ADMIN)` 阈值确实覆盖群主**：`GroupInfoServiceImpl:409-423` 判定为 `role > minRole.getRole()` 即拒，注释明示「0 群主 > 1 管理员 > 2 成员」。传 `ADMIN`(1) 时群主(0)/管理员(1) 通过、成员(2) 拒。**ADR-002 成立**。
2. **`UserContactApplyMapper.xml` 的 `query_condition` 只支持等值，不支持 `IN`**：原 design 假设「把管理员群 ID 集合作为 `IN` 条件入参」**不成立**，必须改 XML。
3. **`queryContactInfo` 的两个 LEFT JOIN 内嵌了 `and a.receive_user_id = #{query.receiveUserId}`**：一旦 `receiveUserId` 不再作为 WHERE 条件，JOIN 会退化成 `= NULL` → **所有行的 `contactName` 变 null**。这是必须同步移除的隐藏地雷。
   - 移除安全性论证：`u` 走 `u.user_id = a.apply_user_id`（PK，1:1）、`g` 走 `g.group_id = a.contact_id`（PK，1:1），**均无扇出、无错配**；原守卫对 1:1 主键 JOIN 完全冗余，其效果已由 WHERE 条件保证。
4. **`UserContactApplyQuery` 的 `receiveUserId` 仅 `loadApply` 与 `ChannelContextUtils:140` 两处使用**（`UserContactApplyServiceImpl:279` 的 `applyQuery` 只用 `applyId`+`status`），故条件语义变更**影响面封闭**。
5. **申请红点存在第二处**：`ChannelContextUtils:139-143` 在 WS INIT 时用 `receiveUserId` 统计 `applyCount`。只改 `loadApply` 会导致「列表里有入群申请但红点不亮」，必须同步。

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 渲染进程 | 新增 `components/GroupJoinDialog.vue`：二维码 token / 邀请 token 两个 tab，各一输入框 + 确认 | 只调 `request`，不碰 Node API |
| 渲染进程 | `Contact.vue` 搜索框旁加「加入群聊」按钮挂载该对话框 | — |
| 渲染进程 | `ContactApply.vue` 列表对 `contactType=GROUP` 标注「入群申请」 | — |

## 2. 接口设计

### 出参变更（2 个）

| 端点 | Method | 入参 | 出参（变更前 → 变更后） | 权限 |
|------|--------|------|------------------------|------|
| `/api/group/qrCode/join` | POST | `qrCodeToken` | `Result<Void>` → **`Result<Integer>`** | 登录用户 |
| `/api/group/invite/join` | POST | `inviteToken` | `Result<Void>` → **`Result<Integer>`** | 登录用户 |

`Integer` 语义 = `JoinTypeEnum`：`0 JOIN` 直接加入 / `1 APPLY` 需审批（`applyAdd` 既有返回约定，前端 `SearchAdd.vue` 已在消费该字段）。

### 错误码

不新增。复用 `ResponseCodeEnum` 既有码：

| 场景 | 码 |
|------|----|
| token 过期/无效 | 抛 `BusinessException("二维码已过期或无效")` → 全局处理器转 `CODE_1001` |
| 群不存在 | `CODE_1001`（沿用既有 `applyAdd` 行为） |
| 已是群成员 | `CODE_1001`（沿用既有 QR/Invite 行为） |
| 非群主/管理员生成二维码 | `CODE_1001`（既有） |

> 无需新开 `2300-2399` 群组域码位。

## 3. 数据模型

**无变更**。复用：

| 表 | 用途 | 变更 |
|----|------|------|
| `group_info.join_type` | 0 直接加入 / 1 管理员同意后加入 | 无 |
| `user_contact_apply` | 好友申请 / 群入群申请共用表，`contact_type` 区分 | 无 |
| `user_contact.role` | 群内角色（群主/管理员/成员） | 无 |

`easychat.sql` 与迁移脚本**无需改动**（已核对，非遗漏）。

## 4. 安全设计

- **鉴权**：两个 join 端点保持 `@GlobalInterceptor`（登录即可，但入群行为本身受 `join_type` 与群存在性约束）
- **权限闭环**：`join_type=1` 时入群**不再由请求方单方决定**，必须经 `dealWithApply` 且审批人身份被 `checkGroupRole(ADMIN)` 校验
- **审批人判定不复用 `receiveUserId` 强等**：`contactType=GROUP` 分支必须走 `checkGroupRole`；`contactType=USER` 分支**保持原有 `receiveUserId` 强等**（好友申请只有被申请人本人能处理，语义不同，不可混用）
- **状态机守卫**：`dealWithApply` 既有 `updateByParam(applyId + status=INIT)` 返回 0 即抛错的守卫保留，防止并发重复审批（`count == 0` 判定）
- **SQL 注入**：不新增手写 SQL，`UserContactApplyQuery` 走既有 MyBatis XML `#{}` 绑定
- **SQL 一致性**：`check-api-contract --strict` 与 `verify_mapper_params` 必须保持 exit 0

## 5. ADR

### ADR-001: 收编到 `applyAdd` 而非新建 `group_join_apply` 表

- 状态: 已接受
- 上下文: 两条野生路径可以各自新建审批表与审批端点，也可以复用既有 `applyAdd`。后者表结构已能表达（`contact_type=GROUP` + `receive_user_id=群主`），前端 `ContactApply.vue` 已有同意/拒绝/拉黑 UI。
- 决策: 复用 `applyAdd` + `user_contact_apply`，**不新建表、不新建端点**。
- 后果: 正面——零 DDL、零迁移、审批 UI 自动复用、改动面收敛在一个 Service 委托。负面——群入群申请与好友申请共用一张表与一个列表接口，靠 `contact_type` 区分；文案上需要在 UI 上标注类型（本变更已含）。

### ADR-002: 审批人用 `checkGroupRole(ADMIN)` 判定，不对群主特判

- 状态: 已接受
- 上下文: `checkGroupRole(userId, groupId, ADMIN)` 的语义是「role 至少为 ADMIN」。群主 role=OWNER，是否满足需确认。
- 决策: 依赖 `checkGroupRole` 的 ADMIN 阈值同时覆盖群主与管理员，**不写 `ownerId.equals(userId) || isAdmin` 这种重复判定**。
- 后果: 正面——判定逻辑单一真源。负面——依赖 `checkGroupRole` 的阈值实现正确，实施时必须读源码确认（见 tasks 1.2 的 [TDD] 前置核查）；若其语义不是"至少"，则需在 `GroupInfoService` 补一个明确方法而非在调用处拼条件。

### ADR-003: 不引入二维码级「免审批」豁免

- 状态: 已拒绝
- 上下文: 实用主义做法是让群主生成二维码时勾选「本次免审批」。用户已在关卡明确否决。
- 决策: 不做。`group_info.join_type` 是入群策略的**唯一真源**。
- 后果: 正面——无第二个真源，杜绝「二维码过期了但豁免标记还在」类不一致。负面——群主想快速拉人只能临时改 `join_type`（`saveGroup` 已支持），多一步操作。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 收编后 `applyAdd` 对群的处理与 QR 原有校验不一致（如缺"已是成员"校验） | 中 | 中 | 委托前**保留** QR/Invite 原有的群存在性与成员校验，不下沉到 `applyAdd`；tasks 1.1 逐条对账 |
| `checkGroupRole(ADMIN)` 阈值语义与预期不符 | 中 | 高（越权或误拒） | tasks 1.2 强制先读实现并写单测固化；不满足则在 `GroupInfoService` 补明确方法 |
| `loadApply` 扩展后分页总数错乱 | 中 | 中 | **禁止**在分页后做内存二次过滤（count 与 list 不同源）。审批可见性写成 XML 里的 `WHERE` 条件，`selectList` 与 `selectCount` 共用同一 `query_condition`，天然同源 |
| 移除 JOIN 冗余守卫后 `contactName` 变 null 或串名 | 中 | 高 | 守卫移除前必须有单测覆盖两条分支（好友申请取昵称 / 群入群申请取群名）；JOIN 两侧均为 PK 1:1，理论上不会扇出（已在设计 §1「已核查」第 3 条论证） |
| 只改 `loadApply` 漏改 WS 红点 | 中 | 中 | 红点与列表共用同一 XML `<if>` 条件（`currentUserId`），两处调用点同时改；tasks 1.3 显式列出两个调用点 |
| 存量已发出的二维码在用户升级后行为突变 | 中 | 低 | 变更即修 bug，属预期；7 天 TTL 自然过期 |
| 前端「加入群聊」入口与 `Search` 入口重复 | 低 | 低 | 新入口只接受 token，与搜索路径互补，UI 上分开表述 |
| 事务边界：委托后 `joinByQrCode` 仍标 `@Transactional`，而 `applyAdd` 自己也标 `@Transactional` | 中 | 低 | 同类事务传播（`REQUIRED` 默认）会合并为同一事务；`applyAdd` 内部抛 `BusinessException` 时整体回滚，符合预期。tasks 1.1 确认无 `REQUIRES_NEW` 冲突 |

## 7. 依赖与前提

- 前提：`scripts/check-api-contract.mjs` 能识别出参类型变化（`Result<Void>` → `Result<Integer>`）不误判为漂移——若误判需同步升级该门禁
- 前提（已核查）：`GroupMemberRoleEnum` 含 `OWNER`(0) / `ADMIN`(1) / `MEMBER`(2)，`checkGroupRole` 阈值语义为「role 数值越小权限越大」
- 前提（已核查）：`UserContactApplyQuery.receiveUserId` 仅 `loadApply` 与 `ChannelContextUtils` 红点两处使用，条件语义变更影响面封闭
- 前提（已核查）：`UserContactApplyMapper.xml` 的 `selectList` 与 `selectCount` 共用 `query_condition`，可在其中加条件保证两者同源
- 无前置 Change；与批次 1（`2026-10-02-config-externalization`，已归档）无耦合
