# Design — 群聊 @ 提及子系统抽纯决策核心

- 关联 Proposal: `openspec/changes/2026-10-04-at-mention-pure-core/proposal.md`
- 创建日期: 2026-10-04

## 1. 既有缺陷详述

### 缺陷 1：搜索口径与显示口径不一致

```js
// 显示（模板 line 101）
{{ item.contactName || item.userId }}
// 过滤（line 288-296）
return (item.contactName || '').toLowerCase().includes(keyword)
```

未设昵称的成员在列表里**显示为 userId**，但用该 userId **搜不到**。
这不是「搜索没做」，是**两个口径对不上**——与 `ChatMessage.vue` 的 `fileType`
双份分派属同一类结构问题。

### 缺陷 2：「双重保护」实际可被绕过

```js
// selectAtAll（line 365-376）确有权限守卫
if (!canAtAll.value) { proxy.Message.warning(...); return }
// 但 buildExtraData（line 491）的条件是：
if (atAllEnabled.value || messageContent.indexOf(AT_ALL_TEXT) >= 0) {
  extra.atAll = true
}
```

`atAllEnabled` 只由面板点击置真。普通成员**手动键入** `@所有人` 时，
`atAllEnabled` 为 false 但 `indexOf >= 0` 成立 → `atAll: true` 被写入并发出 →
服务端 `ChatMessageServiceImpl:235` 的 `checkGroupRole(..., ADMIN)` 抛 `CODE_2305`
→ **整条消息发送失败**。

注释写「双重保护」，但真实的保护**只有服务端那一重**；客户端那一重
只挡住了面板点击，没挡住键盘输入。用户观感是「我好好打了条消息突然发不出去」。

> 注：`正文含 @所有人 也认` 是**有意为之**（注释写明「兼容草稿恢复后重发」）。
> 问题不在于这个兜底，而在于**兜底没有叠加权限判定**。

### 缺陷 3：同一正则、两套去重口径

```js
// buildExtraData（line 486-489）—— 不去重
const matched = messageContent.match(/@(U[A-Za-z0-9]+)/g)
extra.atUserIds = matched.map((item) => item.substring(1))
// buildAtUserIds（line 502-506）—— 去重
return Array.from(new Set(matched.map((item) => item.substring(1)))).join(',')
```

两处**各自复制了同一个正则**。若将来有人只改一处（加严正则 / 改格式），
`extraData.atUserIds` 与 `MessageSendDto.atUserIds` 就会指向不同集合，
而两者都被服务端消费。

## 2. 纯核心边界

抽 `atMentionCore.mjs`，**只放判定，不放副作用**：

| 导出 | 签名 | 纯度 |
|---|---|---|
| `AT_ALL_TEXT` | `'@所有人'` | 常量 |
| `AT_ALL_ROLE` / `AT_ADMIN_ROLE` | `0` / `1` | 常量 |
| `canAtAll(role)` | `(number\|null) => boolean` | 纯 |
| `roleText(role)` | `(any) => string` | 纯 |
| `filterMembers(list, keyword)` | `([]\|null, string\|null) => array` | 纯 |
| `spliceAtText(content, text, start, end)` | `(string, string, number, number) => {content, cursor}` | 纯 |
| `extractAtUserIds(messageContent)` | `(string\|null) => string[]` | 纯 |
| `buildExtraData({quoteInfo, contactType, messageContent, atAllEnabled, role})` | `(obj) => string\|null` | 纯 |
| `buildAtUserIdsField(messageContent)` | `(string\|null) => string\|null` | 纯 |

**留在组件里的**（有副作用，不可抽）：`nextTick` 光标复位、`proxy.Message.warning`、
`proxy.Request` 拉成员、`closeAtPopover`、`watch` 重置。

`spliceAtText` 是关键设计点：`insertAtText` 里 DOM 与字符串交织，
抽成「输入旧内容+光标区间 → 返回新内容+新光标」后，
**光标移动逻辑留在组件、字符串拼接逻辑进核心**，两边各自可测。

## 3. 决策

### ADR-001：`atAll` 的写入条件改为「面板勾选 **且** 有权限」

- **决策**：`atAll: true` 仅当 `canAtAll(role) && (atAllEnabled || 正文含 @所有人)`
- **理由**：缺陷 2 的本质是「兜底分支漏叠加权限判定」。
  权限是**发送侧**的正确性前提，不该只靠服务端兜底——
  服务端 403 的用户观感是「消息发不出去」，客户端剥离的观感是「@ 没生效」。
- **备选与否决理由**：
  - ❌ 维持现状（继续靠服务端 403）：用户会反复重发同一内容直到察觉问题，
    且该失败路径在服务端日志里表现为 `LOGIN`-级别的正常业务异常，噪声大
  - ❌ 完全删掉「正文含 @所有人 也认」的兜底：会破坏草稿恢复后重发
    （草稿只存文本，`atAllEnabled` 是 ref 不持久化）→ **草稿重发会丢 @所有人**

### ADR-002：`filterMembers` 同时匹配昵称与 userId

- **决策**：`keyword` 对 `contactName` **或** `userId` 任一命中即保留（大小写不敏感）
- **理由**：让过滤口径与显示口径一致（缺陷 1）。用户能在列表里看到什么，
  就应该能搜到什么——这是可预期性底线。
- **备选与否决理由**：
  - ❌ 把显示也改成只显 `contactName`：未设昵称者将完全无法辨认，不可接受

### ADR-003：`extraData.atUserIds` 与 `atUserIds` 字段统一去重

- **决策**：两者都基于同一个 `extractAtUserIds()`（返回去重后的数组），
  `extraData` 取数组、`atUserIds` 字段取 `join(',')`
- **理由**：消除「同一正则复制两份」的分叉隐患（缺陷 3）。
  正则**本轮不动**（见 proposal「不在本 Change 内」），但**只留一份**。

### ADR-004：门禁断言「组件确实复用纯核心」

- **决策**：门禁除校验纯核心行为外，另断言 `MessageSend.vue` **不再内联**这些判定
- **理由**：本项目已吃过一次「缺陷原地复活」的亏——`callStoreCore` 抽取后，
  若 store 里内联裸守卫，抽离就白做。门禁必须挡住「绕过核心直接写判定」。
- **实现要点**：断言时**先剥注释**再匹配（AGENTS §2.1 第 7 条——
  本仓注释里大量引用反模式原文，不剥会把说明判成违规）。

## 4. 三层交互时序（对齐前端 AGENTS §4）

本变更**不涉及**主进程 / preload 改动，仅渲染进程内部重构，三层边界不变：

```
[渲染] MessageSend.vue ──import──> atMentionCore.mjs（纯函数，无 electron/window 依赖）
   │                                        │
   │ 输入/点击                                │ 返回判定结果
   ├─> filterMembers / canAtAll / roleText   │
   ├─> spliceAtText ──> nextTick(光标复位)    │  ← DOM 副作用留在组件
   └─> buildExtraData / buildAtUserIds ──> proxy.Request ──> [preload] ──> [主进程]
                                                                          │
                                                        [主进程] wsClient.js ──> 服务端
```

**边界合规**：核心模块**不 import** `electron` / `window` / `axios` / store，
故 node 门禁与 vitest 均可直接 import（这正是抽离的目的）。

## 5. 数据影响

无。`extraData` 结构不变（`quoteId`/`quoteContent`/`quoteNickName`/`atUserIds`/`atAll`），
服务端 `ExtraDataTools.isAtAll` 解析口径不动。

## 6. 风险

| 风险 | 评估 | 缓解 |
|------|------|------|
| 抽离时改变既有行为 | 中 | 抽离与修缺陷**分两个 commit**；先用测试锁定「除三处缺陷外的行为完全等价」 |
| `atAll` 收紧后草稿重发丢标记 | 中 | 兜底条件保留（`正文含 @所有人`），仅**叠加**权限判定；测试覆盖「管理员草稿重发仍带 atAll」 |
| 门禁被绕过（组件内联判定） | 中 | ADR-004 断言 + 变异用例验证 |
| 正则误命中 | **本轮不处理** | 已明确登记为独立项，不假装覆盖 |

## 7. 验证口径

- `npm run test`：vitest 全绿（含新增 spec）
- `npm run lint` / `npm run build`：exit 0，**`vite` 仍为 4.5.14**
- 门禁 `verify_at_mention_core.mjs`：全绿
- 变异 `mutation_at_mention_core.cjs`：**全部捕获**，含：
  ① 去掉 userId 匹配（缺陷 1 复活）② 去掉 `canAtAll` 条件（缺陷 2 复活）
  ③ 去掉去重（缺陷 3 复活）④ 组件内联判定（ADR-004 失效）
