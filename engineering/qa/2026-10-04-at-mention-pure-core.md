# QA — 群聊 @ 提及子系统抽纯决策核心

- 日期: 2026-10-04 ~ 2026-10-05
- 效率等级: **L3**
- Change: `openspec/archive/2026-10-04-at-mention-pure-core`
- 范围:
  - **新增** `easychat-front/src/renderer/src/utils/atMentionCore.mjs`（9 个导出，纯函数）
  - **改造** `easychat-front/src/renderer/src/views/chat/MessageSend.vue`（改为委托核心，删除内联判定）
  - **新增** `src/renderer/src/__tests__/at-mention-core.spec.js`（28 例）
  - **新增** `scripts/verify/verify_at_mention_core.mjs`（19 项断言）+ `mutation_at_mention_core.mjs`（9 变异）
  - `.github/workflows/ci.yml` `gates` job +1
  - 后端 / 数据库 / 接口契约：**零改动**（WS 帧格式、`extraData` 结构、服务端解析口径均不动）

## 验收口径

| # | 口径 | 依据 |
|---|------|------|
| 1 | @ 提及判定成为可独立测试的纯函数 | proposal C1 |
| 2 | 群成员搜索同时匹配昵称与 userId | spec-delta「群成员搜索与显示口径一致」 |
| 3 | `atAll` 写入以角色权限为准 | spec-delta「@所有人」MODIFIED |
| 4 | 兜底保留：管理员草稿重发仍带 `atAll` | spec-delta「权限判定的兜底分支仍保留」 |
| 5 | `extraData.atUserIds` 与 `atUserIds` 字段口径一致且去重 | spec-delta「atUserIds 口径统一且去重」 |
| 6 | 组件复用纯核心，判定未被内联回去 | design ADR-004 |
| 7 | 门禁经变异检验证明有判别力 | AGENTS §2.1 第 1 条 |
| 8 | 抽离未改变三处缺陷以外的行为 | design §6 风险缓解 |

## 实际执行命令与结果

| 命令 | 结果 |
|------|------|
| `npm run lint` | `exit=0` |
| `npm run test` | `Tests 58 passed (58)` / 3 个 spec / `exit=0` |
| `npm run build` | `exit=0`（`built in 271ms / 12ms / 19.95s`，与基线同构） |
| `dependencies` sha1 | `ffd67dccd3d93e6a` —— 与基线**逐字节相同**，零生产依赖变更 |
| `vite` / `electron-vite` | `^4.4.9` / `^1.0.27` —— 未被顶上去 |
| `node scripts/verify/verify_at_mention_core.mjs` | **19 项通过 / 0 失败** / `exit=0` |
| `node scripts/verify/mutation_at_mention_core.mjs` | **9/9 捕获，漏网 0，无效 0** / `exit=0` |
| `verify_frontend_test_base` / `verify_chat_message_dispatch` / `verify_frontend_lint` / `check-openspec-hygiene` | 全部 `exit=0` |
| `ci.yml` | 门禁调用总数 19 → **21**，新增块缩进与同级一致 |

### TDD 红阶段留证

| 阶段 | `exit` | 失败数 | 红因 |
|------|--------|--------|------|
| T1（核心为桩） | 1 | **26** | 全部来自桩的 `UnsupportedOperationException`（78 处）。唯一通过的 constants 用例证明**模块确实被解析到** —— 若模块不存在，失败会是 `Failed to resolve` 而非桩异常 |
| T3（目标行为） | 1 | **9** | 精确命中三处缺陷的 9 条目标断言；「群主/管理员写 atAll」等**本就正确**的用例未红，确认无误伤 |

### 变异明细

```
[基线] ✓ 未变异时门禁全绿
[捕获] ★ 缺陷 1 复活：filterMembers 去掉 userId 匹配
[捕获] ★ 缺陷 2 复活：buildExtraData 去掉 canAtAll 权限条件
[捕获] ★ 缺陷 3 复活：extraData.atUserIds 不去重
[捕获] ★ 兜底被删：草稿重发丢 @所有人（只认面板勾选）
[捕获] 角色判定放宽：普通成员(2)也被放行
[捕获] 单聊也写入 @ 字段（contactType 守卫被删）
[捕获] ADR-004 失效：组件把过滤逻辑内联回去
[捕获] ADR-004 失效：组件内复制第二份 @ 用户 ID 正则
[捕获] 组件未把 myGroupRole 传入核心（atAll 恒判无权限）
=== 结论：9/9 个变异被门禁捕获，漏网 0，无效 0 ===
```

三条 ADR-004 变异是**本 Change 特有的**：若只测核心行为而不测「组件是否复用核心」，
那么有人把判定内联回组件后核心测试仍全绿，**抽离就白做了**。

## 未运行项 / 缺失证据

| 项 | 原因 | 处置 |
|---|---|---|
| 页面截图 | 需启动 Electron 客户端、登录并进入**多人群聊**（才能触发 @ 面板），沙箱无 GUI 与后端活库 | 依 `easychat-front/AGENTS.md` §4.1（2026-10-05 修订）走 **B 形式（可执行行为证据）**：本 Change 属纯逻辑重构 + 判定修复，同一输入下界面渲染**逐像素未变**。Scenario → 断言的逐条对应见 `tasks.md` DoD。**未按「无证据」处理，也未按「有截图」处理** |
| 活体联调（真实发消息验证服务端不 2305） | 需后端 + MySQL + Redis | 判定逻辑已由门禁与 vitest 覆盖，但**端到端未验**。B 形式**不替代**端到端验证 —— 后续有环境时应补一次冒烟 |
| ~~GitHub Actions 实跑~~ | ✅ 2026-10-05 已实跑（run #1，4 job 全绿，含 19 个门禁 step） | 原缺口：当时沙箱无网络 |

> ⚠️ B 形式的边界（§4.1 第 3 条）：若后续发现本次改动**顺带**改变了视觉或交互，
> 必须**补 A 形式截图**。当前逐条核对 12 个 Scenario 后未发现此类情形。


> 📌 **2026-10-05 已补执行**：本条所列的「GitHub Actions 未实跑」「端到端未验」缺口
> 已在具备网络与活库的环境中**实际执行并通过**，证据见
> `engineering/qa/2026-10-05-live-verification.md`（CI run #1 4 job 全绿；
> 冒烟 96/96：`smoke_privacy` 51、`smoke_audit_and_at_all` 16、`smoke_password_session` 29）。

## 结论

**达成**，8 条验收口径全部由自动化验证通过，UI 证据已按 §4.1 分类补齐（B 形式，含逐条对应表）。

## 遗留

| 项 | 状态 |
|---|---|
| 正则 `/@(U[A-Za-z0-9]+)/g` 误命中（`@Ubuntu` → 提取 `buntu`） | **跨端契约**，收紧需前后端协同 + 存量评估。已写入 spec-delta「已知边界」与 `docs/system-facts.md`，**不做假覆盖** |
| 草稿持久化 `atAllEnabled` | 未做。当前靠「正文含 @所有人」兜底，功能不受影响 |
| 补录 UI 截图 | 需有 GUI 的环境 |
