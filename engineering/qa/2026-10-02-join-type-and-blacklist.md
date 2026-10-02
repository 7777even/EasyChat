# QA — 加我方式可配置 + 黑名单可查可解

- Change: `openspec/changes/2026-10-02-join-type-and-blacklist/`
- 执行日期: 2026-10-02
- 环境: Windows / JDK 17（target 1.8）/ Maven / MySQL 5.7 + Redis 在库
- 结论: **通过**

## 1. 范围与验收口径

| 项 | 验收口径 |
|----|----------|
| C1 | 加我方式可更新；非法值 `1001` 且不污染 DB；**保存后立即对新申请生效**；只写 `join_type` 一列 |
| C2 | 黑名单列表含昵称、按最近拉黑倒序；「被拉黑」(5) 不入列；群组不入列；只返回自己的；空黑名单返回空列表 |
| C3 | 解除后**双向行均删除** + 双向缓存清理 + 可重新申请；重复解除 `2401` |
| 安全红线 | `status=5`（他拉黑我）不在我列表、不可由我解除（`2401`）、**双方行均不被删** |
| 缺陷修复 | 拉黑陌生人不再静默失效（upsert）；`DEL` 分支不被波及 |
| 契约 | 3 个新增端点，0 出参变更；`check-api-contract --strict` 0 孤儿 |
| 前端 | build 0 error；新增页 0 error；改动文件 error 数与基线一致 |

## 2. 实际执行的命令与结果

### 2.1 TDD：红阶段（部分，见 §5 说明）

`mvn -B test "-Dtest=UserInfoServiceImplTest,UserContactBlacklistTest"`

首轮因方法不存在而**编译失败**（`找不到符号` × 16）。补上抛 `CODE_1001` 的方法桩后转绿前，先修测试自身缺陷：

```
[ERROR]   UserInfoServiceImplTest.updateJoinType_illegalValue_rejected ? Business 业务异常
[ERROR]   UserContactBlacklistTest.removeBlackList_idempotent:242
```

两条红都是**测试写错**（期望静默不写但实现是抛异常；期望删 2 次但第 2 次在守卫处即抛 2401）。修正后 44/44 绿。

### 2.2 变异检验：证明安全守卫的测试有判别力

因未走完整 TDD 顺序（先写了实现），对安全守卫做变异验证：

```
临时把 removeBlackList 的守卫从
    myContact == null || !BLACKLIST.equals(status)
改为
    myContact == null          （去掉 status 判定）
→ [ERROR]   UserContactBlacklistTest.removeBlackList_cannotRemoveOthersBlacklistOnMe:181
   Tests run: 9, Failures: 1     exit=1
→ 立即还原，重跑 9/9 绿
```

**结论**：安全红线用例确实能抓住「守卫只判行存在」这个高危写法，不是空跑。

### 2.3 全量单测

```
mvn -B test
  ChatMessageServiceImplTest .............. 25/25
  GroupJoinApplyTest .......................  7/7
  GroupJoinJoinTypeContractTest ...........  3/7 → 3/3
  UserContactApplyServiceImplTest ......... 10/10
  UserContactBlacklistTest ................  9/9   ← 新增
  UserContactServiceImplTest .............. 25/25  ← 修正 1 + 新增 1
  UserInfoServiceImplTest ................. 35/35  ← 新增 6
  StringToolsTest ......................... 42/42
  [INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS
```

> 140 → 156，新增 16 例，既有 140 例零回归。

### 2.4 构建与静态门禁

```
mvn -B clean package -DskipTests                    → exit=0
node scripts/check-api-contract.mjs --strict         → exit=0
    [api-contract] 后端路由 115 项；前端调用 113 项；主进程调用 3 项；0 个潜在孤儿 / 0 个潜在漂移
node scripts/verify/verify_mapper_params.mjs         → exit=0（27/27）
node scripts/check-openspec-hygiene.mjs              → exit=0
```

> 中途在未接前端前，门禁曾报「3 个潜在孤儿」（3 个新端点无前端调用），
> 接完前端后归零——**该门禁确实能反映真实接线状态**。

### 2.5 活体冒烟 —— 核心证据

`python scripts/smoke/smoke_blacklist.py` → **39/39 PASS，exit 0**

```
=== 1. C1 加我方式可更新 ===
   [PASS] 更新为「加我时需验证」(1) code=0      [PASS] DB 已写入 1
   [PASS] 更新为「直接加入」(0) code=0          [PASS] DB 已写入 0
   [PASS] 非法 joinType=2 → CODE_1001           [PASS] 非法 joinType=-1 → CODE_1001
   [PASS] ★ 非法值未污染 DB（仍为 0）

=== 2. C1 保存后立即对新申请生效 ===
   [PASS] join_type=0 时 B 直接成为好友（返回 0）  | data=0
   [PASS] DB 中 A→B 已是好友 status=1
   [PASS] 改为 1 后 B 再次申请返回 1（需审批）    | data=1
   [PASS] 已落待处理申请单

=== 3. C2 黑名单列表可查 ===
   [PASS] 空黑名单返回 code=0 且空列表            | len=0
   [PASS] A→B = 4 BLACKLIST                     [PASS] B→A = 5 BLACKLIST_BE（双向生效）
   [PASS] 黑名单含 B                             [PASS] 含对方昵称 contactName | contactName=test
   [PASS] ★ 「被拉黑」(status=5) 不入列
   [PASS] ★ 只返回自己的黑名单（不含 B 拉黑的 C）  [PASS] B 的黑名单含 C

=== 4. C3 解除黑名单（双向删行 + 可重新申请）===
   [PASS] 解除 code=0
   [PASS] ★ 我→他 的行已删                      [PASS] ★ 他→我 的行也删除（无单向残留）
   [PASS] 列表已清空
   [PASS] 重复解除 → CODE_2401                   [PASS] 解除无关系对象 → CODE_2401
   [PASS] ★ 解除后 B 可重新申请（落申请单，非直接加）| data=1

=== 5. 安全红线 无权解除「别人对我的拉黑」 ===
   [PASS] 前置：A→B=5 BLACKLIST_BE（他拉黑我）
   [PASS] ★ 该对象不出现在我的黑名单
   [PASS] ★ 我无法解除 → CODE_2401
   [PASS] ★ 双方关系行均未被删除（他的拉黑仍有效）| A→B=5 B→A=4

=== 5b. 双向拉黑时反向行保留 ===
   [PASS] 我方解除 code=0                        [PASS] 我的拉黑行已删
   [PASS] ★ 对方拉黑我的那行保留（不归我处置）   | B→A=4

=== 6. 群组不进入黑名单 ===
   [PASS] 列表内无 groupId 形态的 contactId

=== 清理 ===
   临时关系行已清、A 的 join_type 已还原为 0

===== 结论：39/39 通过 =====
```

完整快照：`engineering/qa/2026-10-02-join-type-and-blacklist-smoke.txt`

### 2.6 前端

```
npx eslint src/renderer/src/views/setting/Blacklist.vue   → 8 problems (0 errors, 8 warnings)  exit=0
npm run build                                             → built in 22.96s  exit=0
```

改动文件 eslint 对账（git stash 基线 vs 改动后）：

```
基线(HEAD)：755 problems (11 errors, 744 warnings)
改动后    ：808 problems (11 errors, 797 warnings)
```

> **error 数未变（11 → 11）**，存量 error 全为既有问题。+53 warnings 为 `prettier: Delete ␍`（CRLF），
> 与基线该组文件已有的 744 条同类告警一致。
> 首轮 `Blacklist.vue` 有 1 个**我引入的** `vue/require-v-for-key` error，已补 `:key="item.contactId"` 修正。

## 3. 本次发现并当场修复的既有缺陷

**拉黑陌生人不生效（静默失败）** —— 由活体冒烟首次运行暴露：

- **现象**：`POST /contact/addContact2BlackList` 对「搜索到的陌生人」调用返回 `code=0`，界面提示成功，但 `user_contact` **没有任何行**——黑名单根本没加上
- **根因**：`UserContactServiceImpl#removeUserContact` 用 `userContactMapper.updateByUserIdAndContactId`，对**不存在的行是 no-op**。搜索到的陌生人此前从未成为好友，没有行可更新
- **修复**：`BLACKLIST` 分支改走 `insertOrUpdate`（upsert），并补齐 `fillContactRow`（`insertOrUpdate` 的列由 `<if test="bean.xxx != null">` 决定，主键/维度未设会插出脏行）；`DEL` 分支保持 `update` 不变
- **连带影响**：`UserContactServiceImplTest#removeUserContact_blacklist` 原本断言 `update` × 2，**等于把缺陷行为固化成了测试**。已改写为断言 upsert 语义，并新增 `removeUserContact_delStillUsesUpdate` 护栏

## 4. 未运行 / 未覆盖项（不谎报）

| 项 | 原因 | 后续 |
|----|------|------|
| **前端 GUI 交互**（朋友权限切换与失败回滚、黑名单页列表/解除/空态/二次确认、菜单图标渲染） | 沙箱无 GUI | **需本机 `npm run dev` 手动验证并补截图** |
| 菜单图标 `icon-close` 的实际视觉效果 | 仅确认字形存在于 iconfont（`close-bold` e685），未确认视觉语义合适 | GUI 验证时确认；如不合适换 `icon-minus` |
| Redis 缓存清理的运行时效果 | 冒烟只断言了 SQL 行删除，未直接观察缓存键 | 关系行已删，下次 `loadContact` 必回源 DB，行为可预期 |
| `easychat.sql` 与迁移脚本 | 无 DDL 变更 | 已核对确认非遗漏 |
| 朋友圈可见范围 / 在线状态可见性 | 需新增 `user_info` 列（L4） | 归 2②-B，已在 `openspec/specs/privacy-settings/spec.md` 遗留表登记 |

## 5. 过程反思：TDD 顺序没走对

本应「先写测试 → 跑红 → 再写实现」。实际因首轮编译失败（方法不存在），
我在补方法签名时**直接把实现写了**，跳过了行为级红阶段。

**补偿措施**：做了变异检验（§2.2）证明安全守卫的测试有判别力，
并诚实记录于此。**下批改进**：新增方法时先只加 `throw new UnsupportedOperationException()` 桩，
跑到红再填实现——这条与「Windows 禁用 PowerShell 改源码」一并写入 retro 的硬纪律。

## 6. 结论

**通过。** 156 条单测 + 39 条活体断言 + 4 条静态门禁全绿；
两处半成品设置（加我方式、黑名单）已闭环，安全红线经单测（变异检验）+ 冒烟双重锁定，
并顺带修复了「拉黑陌生人静默失效」这个既有缺陷。
遗留：前端 GUI 待本机验证并补截图；朋友圈可见范围与在线状态可见性归 2②-B。
