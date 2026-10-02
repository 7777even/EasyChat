# QA — 群入群审批闭环

- Change: `openspec/changes/2026-10-02-group-join-approval/`
- 执行日期: 2026-10-02
- 环境: Windows / JDK 17（target 1.8）/ Maven / MySQL 5.7 + Redis 在库
- 结论: **通过**

## 1. 范围与验收口径

| 项 | 验收口径 |
|----|----------|
| C1 | 二维码/邀请入群受 `join_type` 管辖；`join_type=1` 时**未入群**且落待处理申请单 |
| C2 | 两 join 端点返回 `joinType`（0/1） |
| C3 | 审批人为群主或群管理员；管理员在 `loadApply` 可见；普通成员既不可见也不可审（2305） |
| C4 | 无效 token / 已是成员 → 1001，不得被误报为「已加入」 |
| 回归护栏 | 好友申请（`contactType=USER`）审批人仍为 `receive_user_id` 本人 |
| 一致性 | `loadApply` 分页 total 与列表同源；WS 申请红点与列表同口径 |
| 契约 | 2 个出参变更为已知；`check-api-contract --strict` 仍 exit 0 |
| 静态门禁 | `verify_mapper_params` / `check-ipc-registration` 均 exit 0 |
| 前端 | `npm run build` 0 error；新增组件 eslint **0 problem** |

## 2. 实际执行的命令与结果

### 2.1 TDD 红阶段（阶段一 任务 1.1/1.2）

新增 `UserContactApplyServiceImplTest`（10 例），在**未实施**时跑：

```
mvn -B test -Dtest=UserContactApplyServiceImplTest
  [ERROR] Tests run: 10, Failures: 2, Errors: 1
  [ERROR]   dealWithApply_groupApply_adminCanApprove ? Business
  [ERROR]   dealWithApply_groupApply_memberRejected:132 expected:<2305> but was:<1001>
  [ERROR]   dealWithApply_groupApply_nonMemberRejected:154 expected:<2304> but was:<1001>
  exit=1
```

> 3 条红全部命中权限缺口本身；其余 7 条（群主审批、好友申请强等、申请不存在、非法 status、并发守卫、拉黑）**改造前即绿**，构成回归护栏。

### 2.2 TDD 红阶段（阶段二 任务 2.1）

新增 `GroupJoinApplyTest`（7 例），在**未实施**时跑：

```
mvn -B test -Dtest=GroupJoinApplyTest
  [ERROR] Tests run: 7, Failures: 1, Errors: 2
  [ERROR]   GroupJoinApplyTest.joinByQrCode_delegatesToApplyAdd
  Wanted but not invoked:
  -> at GroupJoinApplyTest.joinByQrCode_delegatesToApplyAdd(GroupJoinApplyTest.java:92)
  Actually, there were zero interactions with this mock.
  exit=1
```

> "zero interactions with this mock" 是决定性证据：`applyAdd` 从未被调用，
> 当前实现**确实**直接 `addContact` 入群，绕过 `join_type`。2 个 Error 为
> `GroupInviteServiceImpl` 尚无 `userContactApplyService` 字段（反射注入失败），同属未实施。

### 2.3 转绿与全量单测

```
mvn -B test
  ChatMessageServiceImplTest .............. 25/25
  GroupJoinApplyTest .......................  7/7
  GroupJoinJoinTypeContractTest ...........  3/3
  UserContactApplyServiceImplTest ......... 10/10
  UserContactServiceImplTest .............. 24/24
  UserInfoServiceImplTest ................. 29/29
  StringToolsTest ......................... 42/42
  [INFO] Tests run: 140, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
```

> 原 120 例 → 现 140 例，新增 20 例，**既有 120 例零回归**。

### 2.4 构建与静态门禁

```
mvn -B clean package -DskipTests   → exit=0
node scripts/check-api-contract.mjs --strict   → exit=0
    [api-contract] 后端路由 112 项；前端调用 110 项；主进程调用 3 项；0 个潜在孤儿 / 0 个潜在漂移
node scripts/verify/verify_mapper_params.mjs  → exit=0（27/27）
node scripts/check-ipc-registration.mjs --strict → exit=0
node scripts/check-openspec-hygiene.mjs        → exit=0
```

> 出参 `Result<Void>` → `Result<Integer>` 的变更**未**被契约门禁误判为漂移，design 的前置假设成立。

### 2.5 活体冒烟（阶段四 任务 4.1/4.2）—— 核心证据

`python scripts/smoke/smoke_group_join_approval.py` → **48/48 PASS，exit 0**

账号矩阵（本机仅 2 个账号可用统一口令登录，故拆两个临时群）：

| 角色 | 账号 | 登录 | 用途 |
|------|------|------|------|
| A | `test@qq.com` (U69630787860) | ✅ | G_APPLY 扫码申请者；G_ADMIN 成员 role 2→1 |
| B | `karina7710@test.com` (U04259455805) | ✅ | 两个临时群群主 |
| C | `3289228667@qq.com` (U07346173613) | ❌ | 仅作申请单申请人，由 SQL 断言其入群结果 |

关键断言摘录：

```
=== 1. C1/C2 扫码入群受 join_type 管辖（join_type=1）===
   [PASS] 返回 joinType=1（已提交申请待审批） | data=1
   [PASS] ★ 关键：申请人 A **未**被直接拉入群（漏洞已堵）
   [PASS] 已落待处理申请单（status=0，contact_type=1） | count=1
   [PASS] 申请单 receive_user_id 为群主 B
   [PASS] 申请附言标注来源为群二维码
   [PASS] 重复扫码幂等：仍只有 1 条申请单 | count=1

=== 2. C3 群主可见入群申请并审批通过 ===
   [PASS] 群主 B 在申请列表可见本群入群申请 | list=2 条
   [PASS] 入群申请 contactName 为群名（非申请者昵称） | contactName=冒烟临时群
   [PASS] 群主审批通过 code=0
   [PASS] ★ 审批通过后 A 真正入群

=== 3. C4 已是成员与无效 token 不得误报为已加入 ===
   [PASS] 已是群成员再扫码 → 1001 | msg=您已经是该群成员
   [PASS] 无效二维码 token → 1001
   [PASS] 无效邀请 token → 1001

=== 4. C1/C2 join_type=0 时扫码直接入群 ===
   [PASS] 返回 joinType=0（已直接加入） | data=0
   [PASS] 申请人 A 已直接入群
   [PASS] join_type=0 时不产生申请单

=== 5. C1 邀请链接路径与二维码路径对称 ===
   [PASS] 邀请入群返回 joinType=1 | code=0 data=1
   [PASS] ★ 邀请路径同样未直接入群

=== 6. C3 普通群成员：既不可见也不可审 ===
   [PASS] 普通成员 A 看不到本群入群申请
   [PASS] 普通成员 A 审批被拒 code=2305 | msg=无权执行此操作
   [PASS] ★ 被拒后 C 未入群
   [PASS] ★ 被拒后申请单仍为待处理

=== 7. C3 群管理员：可见 + 可审批（修复前的空白能力）===
   [PASS] 群主 B 将 A 设为管理员 code=0
   [PASS] ★ 群管理员 A 能在申请列表看到本群入群申请 | list=1 条
   [PASS] 管理员视角下 contactName 为群名
   [PASS] 管理员视角分页 total 与列表同源
   [PASS] ★ 群管理员 A 审批通过 code=0
   [PASS] ★ 审批通过后 C 真正入群

=== 8. 回归护栏 好友申请审批人仍为被申请人本人 ===
   [PASS] 群管理员 A 不能处理他人好友申请 code=1001
   [PASS] 被申请人 B 本人可处理好友申请 code=0
   [PASS] 好友申请通过后 C 与 B 互为好友

=== 清理临时数据 ===
   [PASS] 清理后临时群已不存在

===== 结论：48/48 通过 =====
```

> 第 1、7 节的两条 ★ 是本次修复的核心证明：修复前这两项**必然失败**
> （扫码直接入群、管理员看不到也审不了）。冒烟自清理通过，临时群/成员行/申请单/会话均已删除。
> 完整输出快照：`engineering/qa/2026-10-02-group-join-approval-smoke.txt`

### 2.6 前端

```
npx eslint src/renderer/src/components/GroupJoinDialog.vue   → 0 problems, exit 0
npm run build                                                 → built in 36.02s, exit 0
```

`Contact.vue` / `ContactApply.vue` 的 eslint 对账（git stash 基线 vs 改动后）：

```
基线(HEAD)：604 problems (12 errors, 592 warnings)
改动后    ：644 problems (12 errors, 632 warnings)
```

> **error 数未变（12 → 12）**，存量 error 全部为既有问题（`vue/require-v-for-key`、
> `onMounted is not defined`、未使用变量），非本次引入。+40 warnings 全为
> `prettier/prettier: Delete ␍`（CRLF），与该两文件基线已有的 580 条同类告警一致。

## 3. 未运行 / 未覆盖项（不谎报）

| 项 | 原因 | 后续 |
|----|------|------|
| **前端 GUI 交互**（点「加入群聊」→ 输 token → 两种提示；申请列表徽标显示「入群申请」） | 沙箱无 GUI | **需本机 `npm run dev` 手动验证**，UI 证据须补截图 |
| WS 申请红点（`ChannelContextUtils`）管理员可见性 | 冒烟未起 WS 连接 | 两处调用点已共用同一 XML 条件，**代码同源已对账**；实际红点变化需 GUI 验证 |
| `easychat.sql` 与迁移脚本 | 无 DDL 变更 | 已核对确认非遗漏 |
| `mvn -B clean package -DskipTests` 的产物 `java -jar` 启动 | 本 Change 未改启动相关 | 批次 1 QA 已覆盖（config-externalization） |
| 前端 lint 门禁 | 见 §4 | 已记债，批次 5 处理 |

## 4. 本次发现并当场处置的自身缺陷

批次 1 交付的 CI 前端 job 跑了 `npx eslint .`。实测发现**该命令在改动前即已失效**：

```
git stash（回到 HEAD）
npx eslint . --ext .js,...  → No files matching the pattern "."  exit=2
npx eslint "src/**/*.{vue,js}"  → 20848 problems (311 errors, 20537 warnings)
```

即 `npm run lint` 从来没有真正跑过。把它设为 CI 闸门会让流水线**恒红**，
等于交付了一个永远失败的 CI。**处置**：移除该步骤并在 ci.yml 写明原因与恢复条件，
把「前端 lint 门禁缺失」记入技术债。这是我在批次 1 引入的缺陷，由本 Change 发现并修正。

## 5. 结论

**通过。** 48 条活体断言 + 140 条单测 + 8 条静态门禁全绿；权限绕过已闭合，
管理员审批能力已补齐，好友申请审批权限未被放宽（负面护栏双端验证）。
遗留：前端 GUI 交互待本机手动验证并补截图证据。
