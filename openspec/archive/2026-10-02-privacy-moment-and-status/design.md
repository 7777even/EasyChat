# Design — 朋友圈可见范围 + 在线状态可见性 + 隐私设置统一页

- 关联 Proposal: 2026-10-02-privacy-moment-and-status/proposal.md
- 创建日期: 2026-10-02

## 1. 架构设计

### 数据模型（4 列，均在 `user_info`）

| 列 | 类型 | 默认 | 语义 |
|----|------|------|------|
| `moment_visibility` | `tinyint(1) NOT NULL DEFAULT 0` | **0 公开** | 用户级默认可见范围，语义与既有 `moment.visibility` **完全对齐**：0 公开 / 1 仅好友 / 2 仅自己 / 3 自定义白名单 / 4 黑名单 |
| `moment_visible_list` | `text NULL` | NULL | 自定义白名单，**JSON 数组字符串**，格式与既有 `moment.visible_list` 一致（`["U1","U2"]`） |
| `moment_invisible_list` | `text NULL` | NULL | 自定义黑名单，JSON 数组字符串，格式与既有 `moment.invisible_list` 一致 |
| `online_status_visible` | `tinyint(1) NOT NULL DEFAULT 1` | **1 展示** | 是否对好友展示在线状态 |

> **默认值选「保持现状」**：`moment_visibility=0`（公开）与既有 `PublishMoment.vue` 的 `visibility: 0` 默认一致；
> `online_status_visible=1` 与既有「无条件广播」一致。存量用户升级后行为**完全不变**，无需通知重新设置。
> `parseList` 复用 `MomentServiceImpl` 的既有私有方法语义（解析时剥 `[]`、按 `,` 切分、去 `"`、去重），
> 不引入新解析器，避免两套格式解析漂移。

### 朋友圈：用户级设置 → 发布默认值 → 单条判定

```
隐私页（用户级）
  Privacy.vue  可见范围 0–4 + 白/黑名单选人（ContactPicker 纯选人，无提交副作用）
        │ POST /userInfo/updateMomentPrivacy { momentVisibility, visibleList, invisibleList }
        ▼
  UserInfoService#updateMomentPrivacy
        │ 白名单校验：visibility ∈ {0..4}，否则 CODE_1001
        │ 名单非空校验：visibility=3 必须有白名单，=4 必须有黑名单，否则 CODE_1001
        │ JSON 数组序列化 + 长度上限（防止单列撑爆）
        ▼
  user_info.moment_visibility / moment_visible_list / moment_invisible_list

发布页（单条，可临时改）
  PublishMoment.vue
        │ 打开时读取 getUserInfo().momentVisibility 作初始值 ← 用户级默认
        │ 用户可临时改；visibility=3/4 时调 UserSelect.vue 选人
        │ 关闭时**不**回写用户级设置（单条 ≠ 默认）
        ▼
  POST /moment/publish { visibility, visibleList, invisibleList, ... }
        ▼
  MomentServiceImpl#publish → moment.visibility / visible_list / invisible_list
        ▼
  MomentServiceImpl#canView  ← 既有判定逻辑，**本变更不改一行**
```

**关键设计**：用户级设置**只是发布时的默认值**，`canView` 判定逻辑**一行都不改**。
这让本变更的回归面被压到最小——新增的只是「配置写入」与「默认值读取」两端。

### 在线状态可见性：主动推帧抹除

```
用户改开关
  Privacy.vue  el-switch
        │ POST /userInfo/updateOnlineStatusVisible { visible: 0|1 }
        ▼
  UserInfoService#updateOnlineStatusVisible → user_info.online_status_visible

  visible=1（重新开启）→ 立即广播当前状态
        └──▶ ChannelContextUtils#broadcastOnlineStatus(我, 当前状态)  → ONLINE_STATUS(22)

  visible=0（关闭）→ 立即推抹除帧
        └──▶ ChannelContextUtils#pushOnlineStatusHidden(我) → ONLINE_STATUS_HIDDEN(27)
                contactId = 我，extendData = { hidden: true }
                仅向在线好友推（与既有 broadcastOnlineStatus 同一取好友逻辑）

此后的状态变更
  ChannelContextUtils#broadcastOnlineStatus(我, status)
        │ 开头：查 online_status_visible == 0 → return（不广播）
        ▼
  （不推任何帧）

好友端
  wsClient.js case 27 → 清掉 onlineStatusMap[contactId] → 转发渲染层 → 状态点消失
```

**为什么用新帧 27 而不是改 22 的 `extendData`**：22 帧的 `extendData` 现在是裸 `Integer`。
改成对象会破坏旧客户端的解析（`extendData.status` 变 undefined → 状态点错乱）。
新增帧类型是 WS 协议向后兼容的标准做法：旧客户端走 `default` 分支忽略即可。

**性能**（已核实 `ChannelContextUtils` 源码后修正）：`ChannelContextUtils` **并不持有**缓存的 `UserInfo`
（INIT 路径在 L99 现查现用），因此不存在「复用已加载对象」这回事。
改为：`broadcastOnlineStatus` 开头做**一次 `selectByUserId` 主键查询**取 `online_status_visible`。
该方法仅在**状态变更时**调用（上线 L89 / 掉线 L181 / 手动切状态），频率是「每人每天几十次」量级，
**不是每条消息**，单次主键查询的开销可忽略。为这点开销引入 Redis 缓存反而增加失效复杂度，不做。

## 2. 接口设计

### 新增（2 个）

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/userInfo/updateMomentPrivacy` | POST | `momentVisibility`（`@NotNull` 0–4）、`visibleList`（JSON 数组，可空）、`invisibleList`（JSON 数组，可空） | `Result<Void>` | 登录用户，仅本人 |
| `/api/userInfo/updateOnlineStatusVisible` | POST | `visible`（`@NotNull` 0/1） | `Result<Void>` | 登录用户，仅本人 |

**均可由 `getUserInfo` 一次性读回**（`UserInfoVO` 补 4 个字段），不新增查询端点。

### WS 帧新增

| 帧 | type | contactId | extendData | 语义 |
|----|------|-----------|------------|------|
| `ONLINE_STATUS_HIDDEN` | **27** | 变更者 userId | `{"hidden": true}` | 抹除该联系人的在线状态展示 |

既有 0–26 **不变**。`MessageTypeEnum` 追加一项即可，帧号不重排。

### 错误码（不新增码位）

| 场景 | 码 |
|------|----|
| `momentVisibility` 不在 `{0..4}` | `1001` |
| `visibility=3` 但白名单为空 / `=4` 但黑名单为空 | `1001` |
| 名单 JSON 格式非法 | `1001` |
| 名单超过长度上限（单列 60000 字符，防 TEXT 撑爆） | `1001` |
| `visible` 不在 `{0,1}` | `1001` |
| 必填项缺省 | `@NotNull` → HTTP 400 |

## 3. 数据模型变更

```sql
-- easychat-migration-011-privacy-settings.sql（幂等：information_schema 判存在）
ALTER TABLE `user_info`
  ADD COLUMN `moment_visibility`    tinyint(1) NOT NULL DEFAULT 0 COMMENT '朋友圈默认可见范围 0公开 1仅好友 2仅自己 3白名单 4黑名单',
  ADD COLUMN `moment_visible_list`  text NULL COMMENT '朋友圈自定义白名单 JSON 数组（visibility=3 生效）',
  ADD COLUMN `moment_invisible_list` text NULL COMMENT '朋友圈自定义黑名单 JSON 数组（visibility=4 生效）',
  ADD COLUMN `online_status_visible` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否对好友展示在线状态 1展示 0隐藏';
```

- MySQL 5.7 **不支持** `ADD COLUMN IF NOT EXISTS` → 用 `information_schema.COLUMNS` 查询 + `PREPARE` 实现幂等
- `easychat.sql` 基线**同步**这 4 列（否则重演 2026-10-02「只改基线不迁移」的漂移事故）
- 存量 `user_info` 4 行：新增列有默认值，**行为不变**

## 4. 安全设计

- **鉴权**：2 个端点全部 `@GlobalInterceptor`，`getTokenUserInfo(request)` 取当前用户，**不接受任何 userId**
- **名单成员合法性**：白/黑名单存的是好友 userId。写入时**一次性校验**：
  用 `UserContactQuery{userId=我, contactType=USER, statusArray=[FRIEND]}` 查一次拿到好友集合，
  再断言名单是好友集合的子集——**一次查询而非 N 次**（已核实 `UserContactService` 无批量校验方法，此为最优解）。
  防止把任意陌生 userId 塞进名单造成脏数据（`canView` 只做 `contains` 判断，危害有限，但应拒绝）
- **不影响 `canView`**：既有判定逻辑一行不改，回归面被压到「配置写入」+「默认值读取」
- **SQL 注入**：全部走既有 `UserInfoMapper#updateByUserId` + `#{}` 绑定
- **SQL 一致性**：`verify_mapper_params` 与 `check-api-contract --strict` 必须保持 exit 0

## 5. ADR

### ADR-001: 用户级设置只作为发布默认值，不改 `canView`

- 状态: 已接受
- 上下文: 两个方案——① 在 `canView` 里再叠一层「发布者用户级设置」过滤；② 用户级设置仅作发布页初值。
- 决策: **② 只作默认值**。
- 后果: 正面——`canView` 一行不改，回归面最小；语义清晰（用户级 = 默认，单条可覆盖）。
  负面——用户改了用户级设置后，**已发布的历史动态不受影响**（仍按各自条目的 visibility 判定）。
  这与微信一致（微信改「不给看」也不追溯历史）。

### ADR-002: 在线状态隐藏用新帧 27，不改 22 的 extendData

- 状态: 已接受
- 上下文: 22 帧 `extendData` 现为裸 `Integer`；改成 `{status, visible}` 会让旧客户端读到 `undefined`。
- 决策: 新增 `ONLINE_STATUS_HIDDEN(27)`。
- 后果: 正面——向后兼容，旧客户端 `default` 分支忽略。负面——帧类型表多一项；`wsClient.js` 需加 case 27
  （若漏加，客户端 `default` 忽略 → 好友端状态点不消失，**不崩但功能不生效**，故必须有门禁/测试覆盖）。

### ADR-003: 隐私设置合并为统一「隐私」页

- 状态: 已接受（2②-A 的 ADR-002 明确留到 2②-B 评估）
- 上下文: 现状「朋友权限在账号设置、黑名单独立页」，再加分页会散成 3 处。
- 决策: 合并为 `views/setting/Privacy.vue`，四区块；`UserInfo.vue` 移除朋友权限项、`Blacklist.vue` 并入。
- 后果: 正面——对齐微信信息架构，单一真源单一入口。
  负面——动 2②-A 已交付的页面有回归面；`/setting/userInfo` 与 `/setting/blacklist` 两个旧路由需处理
  （决策：保留旧路由作 301 重定向到 `/setting/privacy`，避免用户书签与历史栈失效）。

### ADR-004: 名单列用 TEXT 存 JSON 数组，不用新表

- 状态: 已接受
- 上下文: 新建 `user_moment_visibility(user_id, target_id, type)` 关系表更规范，可加索引。
- 决策: 用 `user_info` 的两个 TEXT 列存 JSON 数组，与既有 `moment.visible_list`/`invisible_list` **完全同构**。
- 后果: 正面——零新表、零 JOIN、与既有解析器同源、迁移极简。
  负面——无法对名单成员建索引（但名单只做 `contains` 判断，量级为好友数，够用）；无法反查「谁把我加进了他的白名单」
  （微信也**不**提供这个能力，不算缺失）。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| **WS 帧 27 在 `wsClient.js` 漏加 case** | 中 | 中（不崩，但好友端状态点不消失） | 单测无法覆盖（JS 侧）；`npm run build` 必过；**冒烟用 Playwright/人工 GUI 验证**；QA §未运行项显式列出 |
| `broadcastOnlineStatus` 新增一次 DB 查询 | 低 | 低 | 已核实调用频率为「状态变更时」而非「每条消息」（见 §1 性能段）；单次主键查询，不引入缓存 |
| 迁移脚本不幂等导致重复执行报错 | 中 | 中 | `information_schema` + `PREPARE` 实现幂等；**在存量库实跑两次**验证 |
| 隐私页迁移动到 2②-A 已交付页面引发回归 | 中 | 中 | 保留旧路由重定向；冒烟覆盖旧路由可达；`UserInfo.vue` 只删不加 |
| 白/黑名单选了非好友 id | 中 | 低 | 写入时校验 status=FRIEND，非好友 id 拒绝并 `1001` |
| `visibility=3/4` 但名单为空导致**所有人都看不到 / 都看得到** | 中 | 中 | 写入时强制校验名单非空；`PublishMoment.vue` 选 3/4 未选人时前端拦截 |
| 单条 JSON 撑爆 TEXT 列 | 低 | 中 | 写入前长度校验（60000 字符），超限 `1001` |
| 存量用户升级后朋友圈可见性突变 | 低 | 高 | 默认值选 0（公开），与既有发布页默认一致，行为不变 |
| `getUserInfo` 出参新增 4 字段影响旧前端 | 低 | 低 | 纯增量字段，旧客户端忽略 |

## 7. 依赖与前提

- 前提（**已核实**）：`CopyTools.copy` 用 Spring `BeanUtils.copyProperties`，**同名同类型字段自动复制**。
  `UserInfo` PO 与 `UserInfoVO` 各加 4 个同名字段后，`getUserInfo` 自动带出，无需改 Controller
- 前提（**已核实**）：`UserContactService` **无**批量好友校验方法；采用「一次 `findListByParam` 取好友集合 + 集合包含判断」
- 前提（**已核实**）：`ChannelContextUtils` **不持有**缓存 `UserInfo`，`broadcastOnlineStatus` 需自查一次主键
- 前提（**已核实并修正**）：`UserSelect.vue` **不是通用选人器**——它硬编码了「添加/移除群员」标题，
  且 `submitData` 直接调 `addOrRemoveGroupUser`（副作用提交），仅供群成员管理使用。
  故**新建通用纯选人组件 `components/ContactPicker.vue`**（只拉好友 + 双向选择 + emit id 列表，无任何提交副作用），
  隐私页与发布页共用。`UserSelect.vue` **不动**（避免破坏群成员管理）。
- 前置：2②-A（`openspec/archive/2026-10-02-join-type-and-blacklist`）已闭环，本变更在其上把设置项合并
- 无 DDL 依赖的其他 Change
