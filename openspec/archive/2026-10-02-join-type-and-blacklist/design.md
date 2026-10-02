# Design — 加我方式可配置 + 黑名单可查可解

- 关联 Proposal: 2026-10-02-join-type-and-blacklist/proposal.md
- 创建日期: 2026-10-02

## 1. 架构设计

零 DDL、零新表、纯新增接口。核心是**把两处既有能力接上最后一公里**。

```
加我方式（写入 → 生效）
  UserInfo.vue「朋友权限」单选
        │ POST /userInfo/updateJoinType { joinType }
        ▼
  UserInfoController.updateJoinType
        │ @NotNull + 白名单校验（只接受 0/1）
        ▼
  UserInfoService.updateJoinType
        │ updateByUserId
        ▼
  user_info.join_type
        │
        └──► UserContactApplyServiceImpl#applyAdd:215  userInfoMapper 直读 DB
             ⇒ 下一次好友申请立即按新策略分流（0 直接加 / 1 落申请单）
             ⇒ 无缓存需失效

黑名单（查 + 解）
  Blacklist.vue
        ├─ POST /contact/loadBlackList
        │     └─ user_contact WHERE user_id=我 AND contact_type=0
        │        AND status IN (4)              ← 只有 BLACKLIST，「被拉黑」(5) 不算
        │        [LEFT JOIN user_info 取昵称]  ORDER BY last_update_time DESC
        │
        └─ POST /contact/removeBlackList { contactId }
              └─ 守卫：该行必须存在且 status = BLACKLIST(4)，否则 2401
                 └─ DELETE user_contact (user_id=我, contact_id=他)   ← 我的拉黑记录
                 └─ DELETE user_contact (user_id=他, contact_id=我)   ← 对方的「被拉黑」记录
                    （仅当反向行 status = BLACKLIST_BE(5) 时才删）
                 └─ redisComponet.removeUserContact(双向)  ← 清联系人缓存
```

**为什么解除要删两行**：拉黑时 `removeUserContact(..., BLACKLIST)` 是**双向**改的
（我→他 = 4 BLACKLIST，他→我 = 5 BLACKLIST_BE）。只删自己那行的话，对方侧永远停在
「被拉黑」，会出现「我已解除、对方还是黑名单我」的**单向不一致**。

**为什么反向行要加 status 守卫**：若对方也拉黑了我（我→他=5 BLACKLIST_BE，他→我=4 BLACKLIST），
我此时**没有**解除他黑名单的资格，无条件删反向行等于替对方解除拉黑。
故反向 DELETE 必须在 `status = BLACKLIST_BE(5)` 时才执行；不满足则保留（对方确实拉黑了我）。

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `UserInfoController` | 新增 `updateJoinType` 端点 | 路由 + `@NotNull` + 调 Service，不含业务判断 |
| `UserInfoService` / `Impl` | 新增 `updateJoinType` | 只做存在性校验 + 单列更新 |
| `UserContactController` | 新增 `loadBlackList` / `removeBlackList` 端点 | 路由 + `@NotEmpty` + 调 Service |
| `UserContactService` / `Impl` | 新增 `removeBlackList`；`loadBlackList` 复用 `findListByParam` | 业务规则 + 缓存失效，不感知 HttpServletRequest |
| `UserContactMapper` | **无改动**（`deleteByUserIdAndContactId` 已存在） | 只写 SQL |

**依赖方向检查**：无新增跨 Service 依赖（`UserContactServiceImpl` 已有 `UserContactMapper` 与 `RedisComponet`），无循环依赖风险。

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 渲染进程 | `UserInfo.vue` 朋友权限 → `el-radio-group`（`joinTypeChange` 调接口，失败回滚 UI） | 只经 `proxy.Request`，不碰 Node API |
| 渲染进程 | 新增 `views/setting/Blacklist.vue`：`infinite-scroll` 列表 + 解除（`Confirm` 二次确认）+ 空态 | 同上 |
| 渲染进程 | `Setting.vue` 菜单 + `router/index.js` 路由 | — |
| 主进程 / preload | **无改动**（不新增 IPC 通道） | — |

**失败回滚范式**：`UserInfo.vue` 现有 `themeChange` / `onlineStatusChange` 已用「先改本地 → 调接口 → 失败回滚」
的写法（见该文件 37–70 行），本变更沿用同一范式保持一致。

## 2. 接口设计

### 新增（3 个）

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/userInfo/updateJoinType` | POST | `joinType`（`@NotNull` Integer，0/1） | `Result<Void>` | 登录用户，仅限本人 |
| `/api/contact/loadBlackList` | POST | 无 | `Result<List<UserContact>>` | 登录用户，仅本人拉黑列表 |
| `/api/contact/removeBlackList` | POST | `contactId`（`@NotEmpty`） | `Result<Void>` | 登录用户，仅本人发起的拉黑 |

`UserContact` 出参复用既有 Entity（与 `/contact/loadContact` 完全一致的范式：
`statusArray` + `queryContactUserInfo` + `orderBy`），**不新建 VO**，前端字段名不变。

### 错误码（不新增码位，全部复用）

| 场景 | 码 | 依据 |
|------|----|------|
| `joinType` 为 null | — | `@NotNull` 校验拦截 → 400 |
| `joinType` 非 0/1 | `1001` | `BusinessException(CODE_1001)` 参数非法 |
| 目标不在我的黑名单（不存在 / 已被解除 / 是「被拉黑」status=5） | `2401` | AGENTS §3.1 好友域「非好友关系」，`removeUserContact` 越权场景已用同码 |
| `contactId` 为空 | — | `@NotEmpty` 校验拦截 → 400 |

> 不新开 2100-2199 或 2400-2499 码位。

## 3. 数据模型

**无变更**。复用：

| 表 | 用途 | 变更 |
|----|------|------|
| `user_info.join_type` | 0 直接加入 / 1 加我时需验证 | 无（`tinyint(1)`, 可空 → 空值按 0 处理） |
| `user_contact.status` | 4 BLACKLIST(我拉黑) / 5 BLACKLIST_BE(被拉黑) | 无 |
| `user_contact` PK `(user_id, contact_id)` | 删行不占唯一键 | 无 |

`easychat.sql` 与迁移脚本**无需改动**（已核对，非遗漏）。

## 4. 安全设计

- **鉴权**：3 个端点全部 `@GlobalInterceptor`，`getTokenUserInfo(request)` 取当前用户，**不接受任何 userId 入参**（防越权改他人设置）
- **越权防线**：`removeBlackList` 的守卫是「该 `(我, 他)` 行存在 **且** `status == BLACKLIST(4)`」，
  二者缺一即 `2401`。若只用「行存在」作守卫，**我就能删掉 `status=5`（他拉黑我）的行**，
  等于单方解除他人对我的拉黑——这是本变更最大的安全风险点，单测必须覆盖
- **SQL 注入**：全部走既有 `UserContactQuery` + MyBatis `#{}` 绑定，无字符串拼接
- **输入校验**：`@NotEmpty` / `@NotNull` + Service 层 joinType 白名单
- **SQL 一致性**：`verify_mapper_params` 与 `check-api-contract --strict` 必须保持 exit 0

## 5. ADR

### ADR-001: 解除黑名单 = 删行 + 删反向行（带 status 守卫）

- 状态: 已接受
- 上下文: 三个选项——① 删自己的行；② 删双向行；③ 把 status 改回 `FRIEND(1)`。
- 决策: **删双向行，且反向 DELETE 加 `status = BLACKLIST_BE(5)` 守卫**。
- 后果: 正面——不留脏行，语义与微信「删除黑名单成员」一致；`contact_id` 唯一键释放，对方可重新申请加我（走正常 `applyAdd` 流程）。
  负面——双向删除需要两次 SQL + 事务保证原子性（`@Transactional`）；若对方确实拉黑了我，反向行会被保留，此时「解除我的黑名单」与「他拉黑我」两件事**互相独立**（这是正确语义，不是 bug）。

### ADR-002: `join_type` 原地改 `UserInfo.vue`，不搬到独立「隐私」页

- 状态: 已拒绝（曾考虑搬到新建隐私页）
- 上下文: 微信把「加我的方式」放在设置→隐私里。但本项目 `UserInfo.vue`（账号设置）已有「朋友权限」只读项。
- 决策: 原地把它改为可编辑，**黑名单另立独立页**。
- 后果: 正面——零搬迁、零重复真源（若同时出现在两页，改一处忘另一处会产生不一致）。
  负面——设置项分散在两个页面，不完全对齐微信的信息架构。**2②-B（朋友圈可见范围 / 在线状态）落地时可一并评估是否合并为统一「隐私」页。**

### ADR-003: 黑名单列表不分页

- 状态: 已接受
- 上下文: AGENTS §4 要求分页查询出参统一 `Result<PageResult<T>>`（本项目实际范式是 `PaginationResultVO`）。黑名单理论可累积。
- 决策: 不分页，与既有 `/contact/loadContact` 范式一致。
- 后果: 正面——实现与前端都更简单，`infinite-scroll` 组件仍可用（数据少时一次拉完）。
  负面——极端情况下（ thousands 级拉黑）单次返回过大。**已记入遗留项**，待黑名单成为真实痛点时再加分页。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| **`removeBlackList` 守卫写成「行存在」而非「行存在且 status=BLACKLIST」** | 中 | **高**（可单方解除他人对我的拉黑） | 守卫必须查 status；单测 `removeBlackList_cannotRemoveOthersBlacklistOnMe` 覆盖；QA 冒烟同场景断言 |
| 只删自己那行导致对方侧残留「被拉黑」 | 中 | 中 | ADR-001 双向删除 + `redisComponet.removeUserContact` 双向清缓存；冒烟断言对方行也被清除 |
| `joinType` 前端乐观更新后接口失败，UI 与 DB 不一致 | 中 | 中 | 沿用 `themeChange` 的失败回滚范式；单测/冒烟覆盖非法值分支 |
| `join_type` 存量 NULL | 中 | 低 | `applyAdd` 现有逻辑 `JoinTypeEnum.JOIN.getType().equals(joinType)` 对 null 走「落申请单」分支（偏保守，安全方向）；`updateJoinType` 写入时校验 0/1，**不改存量 NULL** |
| `UserInfoVO.joinType` 为 null 时前端单选无选中项 | 中 | 低 | 前端对 null 默认选中「加我时需验证」（保守），并在 UI 提示 |
| 黑名单列表把「被拉黑」也列出来 | 低 | 中 | `statusArray` 只传 `[BLACKLIST(4)]`；单测断言 |
| `Setting.vue` 菜单图标名不存在导致空白方块 | 低 | 低 | 复用既有 `icon-` 资源（`icon-lock` 若不存在则改用已验证存在的图标名） |

## 7. 依赖与前提

- 前提：`UserContactQuery` 支持 `statusArray` / `queryContactUserInfo` / `orderBy`（已确认，`/contact/loadContact:140` 在用）
- 前提：`UserContactMapper#deleteByUserIdAndContactId` 存在（已确认）
- 前提：`redisComponet.removeUserContact(userId, contactId)` 双向清缓存（已确认，`removeUserContact` 在用）
- 前提：前端 `utils/Confirm.js` 仅支持 `{message, okfun, showCancelBtn, okText}`（无 `cancelfun`），解除确认弹窗按此签名写
- 前提：`UserInfoVO` 已带 `joinType`（已确认，`UserInfo.vue:23` 在读）
- 无前置 Change；与 `2026-10-02-group-join-approval`（已归档）无耦合
