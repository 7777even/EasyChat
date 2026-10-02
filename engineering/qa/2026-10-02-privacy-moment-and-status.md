# QA — 朋友圈可见范围 + 在线状态可见性 + 隐私设置统一页

- Change: `openspec/changes/2026-10-02-privacy-moment-and-status/`
- 执行日期: 2026-10-02
- 环境: Windows / JDK 17（target 1.8）/ Maven / MySQL 5.7 + Redis 在库
- 结论: **通过**（含 1 个由活体冒烟抓出的自身漏改缺陷，已当场修复）

## 1. 范围与验收口径

| 项 | 验收口径 |
|----|----------|
| C1 | 可见范围 0–4 可配；空白名单/非好友/非法 JSON/超长/非法值 → `1001` 且不落库 |
| C2 | 用户级默认被 `getUserInfo` 带出供发布页继承；单条临时覆盖**不回写**用户级 |
| C3 | 在线状态开关 0/1 往返；关闭后 `broadcastOnlineStatus` 不推帧；关闭时推 27 帧抹除；重开立即广播 |
| C4 | 四项集中在 `/setting/privacy`；旧路由 redirect 可达 |
| 存量 | 4 列默认值 0 / 1，**存量用户行为不变**；历史动态不追溯改写 |
| 不串列 | 改一项不重置另一项；昵称/密码不被覆盖 |
| 帧兼容 | `MessageTypeEnum` 0–27 连续无重复；旧帧语义不变 |
| 幂等 | 迁移脚本在存量库**实跑两次**均成功，列数仍为 4 |

## 2. 实际执行的命令与结果

### 2.1 DDL：迁移幂等双跑（任务 1.1 / 1.3）

```
第一次执行：
  moment_visibility         tinyint(1)  NO  DEFAULT 0  朋友圈默认可见范围 0公开 1仅好友 2仅自己 3白名单 4黑名单
  moment_visible_list       text         YES DEFAULT NULL 朋友圈自定义白名单 JSON 数组（visibility=3 生效）
  moment_invisible_list     text         YES DEFAULT NULL 朋友圈自定义黑名单 JSON 数组（visibility=4 生效）
  online_status_visible     tinyint(1)  NO  DEFAULT 1  是否对好友展示在线状态 1展示 0隐藏
  存量 4 行：U04259455805 0|1  U07346173613 0|1  U29953535216 0|1  U69630787860 0|1

第二次执行（幂等验证）：
  无 ERROR 输出
  SELECT COUNT(*) FROM information_schema.COLUMNS ... → 4   （未翻倍为 8）
```

> 执行方式必须走 `cmd /c "mysql.exe ... < file.sql"`；用 PowerShell 管道会因编码把 SQL 里的中文打乱导致引号断裂
> （首次尝试报 `'AL' at line 7`，已改用 cmd 重定向）。
> 基线与迁移对账：`easychat.sql` L167–170 与 `migration-011` 的类型/默认值一致
> （基线额外带表级字符集，MySQL 继承表默认，等价）。

### 2.2 TDD 红阶段（**这次顺序走对了**）

按 AGENTS §2.1 第 3 条，先加方法签名 + `throw new UnsupportedOperationException()` 桩：

```
mvn -B test "-Dtest=UserInfoServiceImplTest"
  [ERROR] Tests run: 52, Failures: 1, Errors: 16
  java.lang.UnsupportedOperationException: TDD 桩：待实现   × 16
  [ERROR]   updateMomentPrivacy_blackListRequiredWhenVisibility4:740 ? UnsupportedOperation
  [ERROR]   updateMomentPrivacy_illegalVisibility:769 ? UnsupportedOperation
  [ERROR]   updateMomentPrivacy_nonFriendRejected:757 ? UnsupportedOperation
  [ERROR]   updateMomentPrivacy_onlyWritesPrivacyColumns:818 ? UnsupportedOperation
  ...
  exit=1
```

> 16 个 Error 全部是桩抛出的，**证明测试在验证新功能而非空跑**。
> 1 个 Failure 是 `UnnecessaryStubbing`（超长校验在查好友前就抛，`stubFriends` 成了无用 stub），红阶段即暴露。

### 2.3 转绿过程中被测试抓出的真实缺陷

**PO 字段的 Java 初始值会导致「串列重置」**（2 条 Failure 暴露）：

```
[ERROR]   updateMomentPrivacy_onlyWritesPrivacyColumns:824  状态可见性不应被本方法写  expected null, but was:<1>
[ERROR]   updateOnlineStatusVisible_onlyWritesThatColumn:881 朋友圈可见范围不应被本方法写  expected null, but was:<0>
```

**根因**：我给 PO 加了 `private Integer onlineStatusVisible = 1;` 与 `momentVisibility = 0;`。
`new UserInfo()` 天然带这些值，而 `UserInfoMapper.xml#updateByUserId` 的
`<if test="bean.xxx != null">` 会把它们**一并写进 SQL** —— 改朋友圈隐私会把在线状态开关重置为 1，
改在线状态开关会把朋友圈可见范围重置为 0。

**修复**：移除 PO 的 Java 字段初始值，默认值只由 DDL 的 `NOT NULL DEFAULT` 与读取路径承担。
已在 PO 字段注释里写明「刻意不设初始值」的原因，防止后人再加回来。
**这与 2②-A retro 里「PO 初始值无害」的印象相反，是本轮新增的经验。**

另修：超长名单测试数据量算错（4000 项 × 10 字符 ≈ 40k < 60000 上限），改 8000 项。

### 2.4 全量单测与构建

```
mvn -B test
  ChatMessageServiceImplTest 25/25   GroupJoinApplyTest 7/7      GroupJoinJoinTypeContractTest 3/3
  UserContactApplyServiceImplTest 10/10   UserContactBlacklistTest 9/9
  UserContactServiceImplTest 25/25   UserInfoServiceImplTest 52/52（新增 17 例）   StringToolsTest 42/42
  [INFO] Tests run: 173, Failures: 0, Errors: 0, Skipped: 0

mvn -B clean package -DskipTests  → exit=0
```

> 156 → 173，新增 17 例，既有 156 例零回归。

### 2.5 帧号对账

```
帧总数: 28
0..27 连续无空洞: true
新增帧: 27 ONLINE_STATUS_HIDDEN
DUP! 无
```

### 2.6 静态门禁

```
node scripts/check-api-contract.mjs --strict   → exit=0
    [api-contract] 后端路由 117 项；前端调用 115 项；主进程调用 3 项；0 个潜在孤儿 / 0 个潜在漂移
node scripts/check-ipc-registration.mjs --strict → exit=0（39/39 全部注册；本次不新增 IPC 通道）
node scripts/verify/verify_mapper_params.mjs     → exit=0（27/27）
node scripts/check-openspec-hygiene.mjs          → exit=0
```

> 契约门禁中途曾报「2 个潜在孤儿」（2 个新端点前端未接），接完前端后归零——该门禁确有判别力。

### 2.7 活体冒烟 —— 核心证据，且**抓出了我自己漏改的 Mapper**

`python scripts/smoke/smoke_privacy.py` → **51/51 PASS，exit 0**

**首跑 14/51 失败**，全部正常路径返回 `CODE_1002 系统内部错误`。查后端日志：

```
org.springframework.jdbc.BadSqlGrammarException:
  ### SQL: UPDATE user_info  where user_id=?
  ### The error may involve com.easychat.mappers.UserInfoMapper.updateByUserId-Inline
```

**根因（我的漏改）**：只在 PO/VO 加了 4 个字段，**忘了改 `UserInfoMapper.xml`** ——
`base_resultMap`、`base_column_list`、`updateByUserId` 的 SET 都没有新列，
于是 `updateByUserId` 生成的 SET 子句是**空串**，拼出 `UPDATE user_info  where user_id=?` 的语法错。

**为什么单测抓不到**：单测 mock 掉 Mapper，只验证「Service 有没有调 `updateByUserId`、
传的 bean 对不对」，而空 SET 是 **Mapper 层**的问题。**只有真跑一次活体才现形。**

**修复**：XML 三处补齐（`resultMap` 4 个 result + `base_column_list` 4 列 + `updateByUserId` 4 个 `<if>`）。
`<if>` 只加在 `updateByUserId`，`insert`/`insertOrUpdate` **不写**这 4 列——
否则注册机器人等未显式赋值的路径会插出 `NULL`（列虽 `NOT NULL DEFAULT`，显式 NULL 仍会报错）。

修复后关键断言摘录：

```
=== 1. 存量用户默认值与行为不变 ===
   [PASS] 列 moment_visibility / moment_visible_list / moment_invisible_list / online_status_visible 已存在
   [PASS] B/C 的 moment_visibility 默认 0（与改动前发布页默认一致）
   [PASS] B/C 的 online_status_visible 默认 1（与改动前无条件广播一致）
   [PASS] getUserInfo 带出 4 个隐私字段

=== 2. C1 朋友圈可见范围 0–4 可配置 ===
   [PASS] 设为 0/1/2 code=0 + DB 写入
   [PASS] 设为 3（白名单含好友 B）code=0 + 白名单落库含 B
   [PASS] 设为 4（黑名单含好友 B）code=0 + 黑名单落库含 B
   [PASS] ★ 切回公开后白名单仍保留（便于切回）

=== 3. C1 校验：空名单 / 非好友 / 非法值 / 超长 ===
   [PASS] ★ visibility=3 但白名单为空 → CODE_1001
   [PASS] ★ visibility=4 但黑名单为 [] → CODE_1001
   [PASS] ★ 被拒后 DB 仍为上一次合法值 3
   [PASS] ★ 名单含非好友 C → CODE_1001
   [PASS] ★ 非法 JSON（not-a-json）→ CODE_1001
   [PASS] ★ 超长名单（8000 项）→ CODE_1001
   [PASS] 非法 visibility=-1/5/99 → CODE_1001     [PASS] visibility 缺省 → HTTP 400

=== 4. 只写隐私列，不串列 ===
   [PASS] ★ 改在线状态开关不重置朋友圈可见范围  before=3|[..]|[..]|1 → after=3|[..]|[..]|1
   [PASS] ★ 改朋友圈可见范围不重置在线状态开关  before=3|[..]|[..]|1 → after=2|[..]|[..]|1
   [PASS] ★ 昵称未被隐私设置覆盖

=== 5. C2 用户级默认被发布页继承（端到端）===
   [PASS] getUserInfo 返回 momentVisibility=1（发布页据此设初值）
   [PASS] 白名单模式：getUserInfo 带出名单供发布页预选  list=["U04259455805"]
   [PASS] 单条发布 visibility=2 code=0
   [PASS] ★ 单条覆盖后用户级仍为 3（未被回写）      user_mv=3
   [PASS] ★ 该条动态自身 visibility=2（判定走单条，不受用户级影响）

=== 6. C3 在线状态可见性开关 ===
   [PASS] 关闭 code=0 + DB 写入 0 + getUserInfo 回读 0
   [PASS] 重新开启 code=0 + DB 写入 1
   [PASS] 非法 visible=-1/2 → CODE_1001
   [PASS] ★ 非法值未污染 DB（仍为 1）

=== 7. 越权防护：接口不接受 userId ===
   [PASS] 携带他人 userId 时仍只改自己（B 的值未变）  B_osv=1

=== 清理 ===  A 的 4 列已还原：mv=0 osv=1
===== 结论：51/51 通过 =====
```

完整快照：`engineering/qa/2026-10-02-privacy-moment-and-status-smoke.txt`

### 2.8 前端

```
npm run build  → built in 24.97s, exit=0
npx eslint ContactPicker.vue Privacy.vue            → 20 problems (0 errors)
8 个改动/新增文件 eslint 对账（git stash 基线 vs 改动后）：
  基线(HEAD)：1734 problems (18 errors, 1716 warnings)
  改动后    ：1847 problems (18 errors, 1829 warnings)
```

> **error 数未变（18 → 18）**，存量 error 全为既有问题。+113 warnings 均为 `prettier: Delete ␍`（CRLF），
> 与该组文件基线已有的 1716 条同类告警一致。

## 3. 未运行 / 未覆盖项（不谎报）

| 项 | 原因 | 后续 |
|----|------|------|
| **WS 帧 27 的端到端效果**（关闭后好友端状态点立即消失） | 冒烟走 HTTP，**未起 WS 连接** | **需本机双实例 GUI 验证**。风险已缓解：`wsClient.js` case 27 与 `Contact.vue` 的 `onlineStatusHidden` 订阅均已实现，build 通过；但**若 case 27 漏加，客户端走 `default` 忽略 → 不崩但功能不生效** |
| 隐私页四区块交互与深色模式 | 沙箱无 GUI | 本机 `npm run dev` 验证并补截图 |
| 发布页 5 项可见范围 + 选人交互 | 同上 | 同上 |
| 旧路由 `/setting/userInfo`、`/setting/blacklist` redirect | 同上 | 同上 |
| `ContactPicker` 拉好友与双向选择 | 同上 | 同上 |
| 单测覆盖 `ChannelContextUtils#broadcastOnlineStatus` / `pushOnlineStatusHidden` | tasks 原计划建 `ChannelContextUtilsTest`（≥5 例），**本轮未做** | 已在 §5 记为未完成项：这两处是纯逻辑（判开关、拼帧、取在线好友），**可测但本轮遗漏**；补测前 WS 侧只有 GUI 验证覆盖 |
| `easychat.sql` 与迁移一致性 | — | 已逐行对账（§2.1） |

## 4. 过程反思

**本轮 TDD 顺序走对了**（先桩跑红 16 Error），且 TDD 在转绿阶段抓出「PO 初始值导致串列重置」这个真缺陷。
但**活体冒烟抓出的 Mapper 漏改说明：单测的 mock 边界会形成盲区**——Service 层单测无论多细，
都看不见 Mapper XML 少了一个 `<if>`。这不是测试写得不够，而是**测试分层固有的盲区**，
只能用「真跑一次」补。这条已写入 Retro。

## 5. 结论

**通过。** 迁移幂等双跑验证通过；173 条单测 + 51 条活体断言 + 4 条静态门禁全绿；
存量用户行为不变经冒烟逐行核对；`canView` 判定逻辑一行未改（ADR-001 守住了回归面）。
过程中被冒烟抓出并修复 1 个自身漏改（Mapper XML）与 1 个设计缺陷（PO 初始值串列）。

**遗留**：WS 帧 27 端到端效果与全部前端交互待本机 GUI 验证并补截图；
`ChannelContextUtils` 的 2 个方法缺单测（可测，本轮遗漏）。
