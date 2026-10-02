# QA — WS 帧协议门禁 + ChannelContextUtils 单测（补 2②-B 遗留）

- 范围: 补 `openspec/archive/2026-10-02-privacy-moment-and-status/tasks.md` 遗留两项
- 执行日期: 2026-10-03
- 分级: **L2**（新增测试 + 新增门禁脚本，不改业务能力/接口契约；顺带修一处门禁自解析的健壮性）
- 结论: **通过**（并附带发现一个**既有缺陷**：位置消息 / 语音消息端到端未接通）

## 1. 范围与验收口径

| 项 | 验收口径 |
|----|----------|
| 门禁判别力 | 变异检验：故意破坏帧协议若干处，门禁须 exit 1；还原后须 exit 0 |
| 单测判别力 | 变异检验：故意破坏 `ChannelContextUtils` 隐私逻辑 8 处，测试须转红 |
| 单测覆盖 | `broadcastOnlineStatus` 开关判定 + `pushOnlineStatusHidden` 帧构造 ≥5 例 |
| 门禁接入 | `pre-push` hook + CI + `AGENTS.md` §10 + `scripts/README.md` 四处登记齐全 |
| 既有回归 | `mvn test` 186/186（173 → +13）；`mvn package` exit 0 |

## 2. 实际执行

### 2.1 新增单测：13 例，全绿

```
mvn -B test "-Dtest=ChannelContextUtilsOnlineStatusTest"
  [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

覆盖点：

| 用例 | 断言的行为 |
|------|-----------|
| `hidden_noFramePushed` | 开关=0 → `sendRawToUser` 一次都不调 |
| `hidden_skipsFriendLookup` | 开关=0 → 连 `getUserContactList` 都不查（提前 return） |
| `visible_pushesToOnlineFriendOnly` | 开关=1 → 推在线好友、不推离线好友；帧内 `messageType/contactId/sendUserId/extendData` 逐字段校验 |
| `hidden_blocksAllStatusValues` | 遍历 `OnlineStatusEnum` 全部值均被拦截（不只 ONLINE） |
| `userNotFound_defaultsToVisible` | 查不到用户 → 按**展示**处理，不误伤正常用户 |
| `nullSwitch_defaultsToVisible` | 开关列为 NULL（历史脏数据）→ 按展示处理 |
| `blankUserId_returnsEarly` | userId 空 → 连 SQL 都不发 |
| `pushesFrame27WithHiddenMarker` | 抹除帧：`messageType=27` / `contactId=自己` / `sendUserId=自己` / `extendData.hidden=true` |
| `onlyToOnlineFriends` | 抹除帧只推在线好友 |
| `ignoresSwitchValue` | **抹除帧不读开关**（否则关不掉残留状态点）；`verify(never()).selectByUserId` |
| `frameNumberDiffersFromOnlineStatus` | 帧号 27 ≠ 22 硬断言 |
| `noFriends_noFrame` | 无好友 / userId 空 → 不推帧不抛异常 |
| `hiddenSwitch_doesNotAffectMessageDelivery` | 开关=0 时好友**仍能收到聊天消息**（打桩 `sendRawToUser`，从 `EmbeddedChannel.readOutbound()` 取真实帧校验） |

> 实现手法：Mockito spy 拦 `sendRawToUser`（真正写 Netty 帧的方法）；
> 「是否在线」通过往静态 `USER_CONTEXT_MAP` 放**含 `EmbeddedChannel` 的** `DefaultChannelGroup` 控制。

### 2.2 单测变异检验 — 8/8 全部捕获

```
=== 变异检验：ChannelContextUtilsOnlineStatusTest ===
  [CAUGHT] 变异1 去掉广播前的开关判定（关闭隐私后仍广播） → exit=1
            broadcastOnlineStatus_hidden_blocksAllStatusValues:191
            broadcastOnlineStatus_hidden_noFramePushed:145
            broadcastOnlineStatus_hidden_skipsFriendLookup:155
  [CAUGHT] 变异2 isOnlineStatusVisible 恒返回 true（开关完全失效） → exit=1
  [CAUGHT] 变异3 抹除帧错用 ONLINE_STATUS(22) 而非 27 → exit=1
            pushOnlineStatusHidden_pushesFrame27WithHiddenMarker:248 expected:<27> but was:<22>
  [CAUGHT] 变异4 抹除帧不带 hidden 标记（客户端无从判定） → exit=1
            pushOnlineStatusHidden_pushesFrame27WithHiddenMarker:251 extendData 缺少 hidden=true 标记
  [CAUGHT] 变异5 抹除帧也推给离线好友（浪费且可能泄漏状态） → exit=1
            pushOnlineStatusHidden_onlyToOnlineFriends:265
  [CAUGHT] 变异6 抹除帧改成读开关（导致关不掉残留状态点） → exit=1
            pushOnlineStatusHidden_ignoresSwitchValue:283
  [CAUGHT] 变异7 抹除帧 contactId 写错成自己以外的值 → exit=1
            expected:<[U_self]> but was:<[WRONG_ID]>
  [CAUGHT] 变异8 抹除帧不设 sendUserId（客户端无法识别来源） → exit=1
            expected:<[U_self]> but was:<[null]>
  [PASS] 还原后基线复跑 exit=0
结论：全部变异均被捕获，测试有判别力   mutation-exit=0
```

> **踩坑记录**：首轮变异脚本全部显示 `exit=null` 且误判为「CAUGHT」。
> 根因：Windows 上 `mvn` 是 `mvn.cmd`，`execFileSync` 无法直接执行 `.cmd`，
> ENOENT 使 `e.status` 为 `null`，被 `!== 0` 判成「捕获成功」。
> 已改为 `execFileSync('cmd', ['/c', MVN, ...])`，并把 `null` 显式归一为失败——
> **这正是 AGENTS §2.1 第 1 条「门禁须实跑有判别力」的同类陷阱**：
> 一个永远「通过」的判据比没有判据更危险。

### 2.3 新增门禁：9/9 变异全部捕获

```
=== 变异检验：verify_ws_frame_parity.mjs ===
  [CAUGHT] 变异1 客户端加了服务端不存在的帧（协议漂移）      → exit=1
  [CAUGHT] 变异2 客户端删掉 case 22（在线状态帧静默失效）    → exit=1
  [CAUGHT] 变异7 客户端把 case 14 改成别的号（撤回消息失效） → exit=1
  [CAUGHT] 变异3 客户端删掉 case 19（群公告帧静默失效）      → exit=1
  [CAUGHT] 变异4 服务端帧号重复（27 改成 26）                → exit=1
  [CAUGHT] 变异5 服务端新增帧 28 但客户端未接（意图未声明）  → exit=1
  [CAUGHT] 变异6 落库白名单被清空（结构改写 → 检查失效）     → exit=1
  [CAUGHT] 变异8 落库白名单引用了枚举里不存在的项             → exit=1
  [CAUGHT] 变异9 KNOWN_GAP 过期（25 已落库但没删登记）        → exit=1
  [PASS] 还原后基线复跑 exit=0
结论：全部变异均被捕获，门禁有判别力   mutation-exit=0
```

> 变异 6/8 是**自查修出来的**：首版把「解析不到落库白名单」当成 WARN 跳过，
> 变异检验直接把它判为 MISSED。已改为 **ERROR 阻断** + 枚举项不存在时 **throw**——
> 门禁在结构漂移后绝不能变成永远绿的空壳。

### 2.4 门禁首次运行即发现既有缺陷（**非本次引入**）

首次运行报 ERROR：位置消息 `type=25` 在客户端无 `case`。追查后确认是三层断裂：

| 层 | LOCATION(25) | VOICE(24) |
|----|-------------|-----------|
| `ChatMessageServiceImpl` 落库白名单（仅 1/2/3/5） | ❌ 不落 `chat_message` 表 | ❌ 同 |
| `wsClient.js` case | ❌ 无 → 对端实时收不到 | ❌ 无 |
| `Chat.vue:136` 分发条件 | ❌ 不含 → 即使有也不渲染 | ❌ 不含 |
| 渲染组件 | `ChatMessageLocation` 已接但因前两层断而**从不执行** | `ChatMessageVoice.vue` **死组件，从未被 import** |

DB 实证：

```
SELECT message_type, COUNT(*) FROM chat_message GROUP BY message_type;
  1  2
  2  6
```

**只有 1 和 2，0 条 24/25** → 这两个功能从未被真实使用过，常规冒烟与既有 spec 验收都发现不了。

**未修复，只登记**。理由：修复需改服务端落库白名单（业务能力变更 → **L3**），
且语音需接死组件并验证音频播放，属独立 Change。当前登记于门禁 `KNOWN_GAP`，
每次运行都打印，保持技术债可见；已同步 `docs/system-facts.md` 与
`openspec/specs/privacy-settings/spec.md` 遗留表。

### 2.5 全量回归与门禁

```
mvn -B test   → [INFO] Tests run: 186, Failures: 0, Errors: 0, Skipped: 0   （173 → +13）
mvn -B clean package -DskipTests → exit=0

门禁（9 条全 exit 0）：
  node scripts/check-api-contract.mjs --strict          exit=0
  node scripts/check-ipc-registration.mjs --strict      exit=0
  node scripts/check-openspec-hygiene.mjs               exit=0
  node scripts/verify/verify_mapper_params.mjs          exit=0
  node scripts/verify/verify_password_handoff.mjs      exit=0
  node scripts/verify/verify_no_hardcoded_secret.mjs   exit=0
  node scripts/verify/verify_ws_frame_parity.mjs       exit=0
  node scripts/verify/verify_virtual_core.mjs          exit=0
  node scripts/verify/verify_call_core.mjs             exit=0
```

门禁接入四处已验证生效（`node scripts/setup-git-hooks.mjs` 实跑后读 `.git/hooks/pre-push`
与 `pre-push.bat` 确认含新行）：

| 位置 | 内容 |
|------|------|
| `.github/workflows/ci.yml` | 新增 step「WebSocket 帧协议两端对账」+ 注释写明为何加 |
| `scripts/setup-git-hooks.mjs` | `pre-push` 的 sh 与 bat 双份均已加 |
| `AGENTS.md` §10 | 门禁表新增一行 + 阻断条件 |
| `AGENTS.md` §7.4（新增小节） | WS 帧协议四条硬要求 |
| `scripts/README.md` | 脚本清单补全 4 个此前漏登记的脚本 + 新增「WS 帧协议对账」说明章 |

## 3. 未运行 / 未覆盖项

| 项 | 原因 | 后续 |
|----|------|------|
| **位置消息 / 语音消息修复** | 需改服务端落库白名单 = L3 业务能力变更 | 独立 Change；当前 `KNOWN_GAP` 登记 |
| WS 帧 27 端到端效果（好友端状态点立即消失） | 冒烟走 HTTP 未起 WS；沙箱无 GUI | 本机双实例 GUI 验证（2②-B 已登记，仍未做） |
| 隐私页 / 发布页 / `ContactPicker` 前端交互 | 同上 | 同上（2②-B 已登记，仍未做） |
| 门禁在**真实 CI 环境**的表现 | 本机实跑 exit 0，但未推送触发过 Actions | 首次 push 后看 CI 结果 |

## 4. 结论

**通过。** 两项遗留均已补齐且经变异检验证明有判别力（单测 8/8、门禁 9/9），
全量 186/186、9 条门禁 exit 0、`package` exit 0。

附带发现一个既有缺陷（位置消息 / 语音消息端到端未接通），已如实登记未修——
修复属 L3，需人工确认后走独立 Change。