# Proposal — 群聊 @ 提及子系统抽纯决策核心

- 创建日期: 2026-10-04
- 效率等级: **L3**

## Why

`MessageSend.vue` 的 @ 提及逻辑（`canAtAll` / `roleText` / `filteredAtMemberList` /
`insertAtText` / `buildExtraData` / `buildAtUserIds`）与 DOM、store、`proxy.Request`
深度缠绕，**在 node 中无法 import，故零自动化覆盖**。而这段逻辑恰好是本项目
缺陷密度最高的形态——**「条件漏一项 / 判定与实际不符 → 静默失效」**。

通读即发现三处既有缺陷（详见 design.md §1，均为**读代码发现**，非推测）：

| # | 缺陷 | 后果 |
|---|------|------|
| 1 | `filteredAtMemberList` 只按 `contactName` 过滤，而面板**显示**用的是 `contactName \|\| item.userId` | 未设昵称的成员**看得见却搜不到** |
| 2 | `selectAtAll` 注释自称「双重保护」，但 `buildExtraData` 的条件是 `atAllEnabled \|\| 正文含 '@所有人'` | 普通成员**手动输入**即可绕过客户端保护 → 服务端 `CODE_2305` → **整条消息被拒** |
| 3 | `buildExtraData` 与 `buildAtUserIds` 用同一正则，但前者**不去重**、后者去重 | 两处口径已分叉，后续改动极易只改一处 |

## What Changes

- 前端:
  - **新增** `src/renderer/src/utils/atMentionCore.mjs`：把上述 6 处判定抽为**纯函数**
    （无 DOM / 无 store / 无 `proxy` 依赖，node 可直接 import）
  - `MessageSend.vue` 改为委托纯核心，**组件内不再保留判定逻辑**
  - 修三处缺陷：搜索同时匹配昵称与 userId；`atAll` 需角色权限；`atUserIds` 统一去重口径
  - **新增** `scripts/verify/verify_at_mention_core.mjs`（门禁）
  - **新增** `scripts/verify/mutation_at_mention_core.cjs`（反向验证）
  - **新增** `src/renderer/src/__tests__/at-mention-core.spec.js`（vitest）
- 后端: **无改动**
- 数据库: 无

## Capabilities

- C1: @ 提及的判定逻辑成为可独立测试的纯函数（无环境依赖）
- C2: 群成员搜索同时匹配昵称与 userId，与面板显示口径一致
- C3: `@所有人` 标记的写入以**角色权限**为准，非管理员不得写入
- C4: `extraData.atUserIds` 与 `MessageSendDto.atUserIds` 使用同一套去重口径
- C5: 判定逻辑具备机控守卫，且守卫经变异检验证明有判别力

## Impact

- 对外接口: **无变更**（WS 帧格式、`extraData` 结构、后端解析口径均不动）
- 存量数据: 无（`extraData` 结构不变，仅 `atAll` 的**产生条件**收紧）
- **行为变化**：普通成员在正文中手打 `@所有人` 时，**由「消息被服务端拒绝（CODE_2305）」改为
  「消息正常发出，`atAll` 不写入」**。这是本变更唯一的用户可见行为变化。
- 性能: 无（纯字符串操作，且过滤逻辑从每次渲染内联改为同等复杂度）
- 回滚方案: 单文件 revert（核心 + 组件委托 + 门禁）；无数据迁移、无配置项

## 不在本 Change 内

| 项 | 原因 |
|---|---|
| 正则 `/@(U[A-Za-z0-9]+)/g` 的误命中（`@Ubuntu` → 提取 `buntu`） | **跨端契约**：服务端 `ExtraDataTools.isAtAll` 与 `atUserIds` 消费同一格式，收紧需前后端协同 + 存量数据评估 → 独立 Change |
| `draft` 恢复后 `atAllEnabled` 丢失 | 属草稿功能，不在本次抽离范围 |

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 2026-10-04 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
