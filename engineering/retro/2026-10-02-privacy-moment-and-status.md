# Retro — 朋友圈可见范围 + 在线状态可见性 + 隐私设置统一页

- Change: `openspec/changes/2026-10-02-privacy-moment-and-status/`
- 日期: 2026-10-02

## 一、做得好

1. **ADR-001 把回归面压到了最小**。「用户级设置只作发布默认值，不改 `canView`」这一个决定，
   让整个朋友圈可见范围改造的爆炸半径收缩为「配置写入」+「默认值读取」两端，
   `MomentServiceImpl#canView`（朋友圈最核心的判定逻辑）**一行未改**。
   如果当初选了「在 `canView` 里再叠一层用户级过滤」，回归面会覆盖全部 5 个 `visibility` 分支。
2. **默认值刻意选「与现状一致」**。`moment_visibility=0`（公开）正好等于 `PublishMoment.vue` 改动前的
   `visibility: 0` 默认；`online_status_visible=1` 等于改动前的「无条件广播」。
   结果是**存量用户升级后行为完全不变，无需通知任何人重新设置**——
   这是隐私类改动最容易踩的坑（默认值选错 = 全量用户朋友圈变私密）。
   冒烟专门加了「存量行为不变」一节逐行核对。
3. **TDD 顺序这次走对了**。先加 `throw new UnsupportedOperationException()` 桩，跑出 16 个 Error
   才填实现（AGENTS §2.1 第 3 条是上一批 retro 立下的纪律，本批首次执行到位）。
   而且 TDD 在**转绿阶段**就抓出了「PO 初始值串列重置」这个真缺陷。
4. **把 `parseList` 抽成 `IdListTools` 消除双解析器**。ADR-004 最初想「与既有解析同构」，
   如果只是在新代码里抄一份，两套解析迟早漂移。改成「逐字搬移 + `MomentServiceImpl` 委托」，
   行为零变化，但两处名单从此不可能解析出不同结果。
5. **核实 design 的每个前提，而不是照抄**。`UserSelect.vue` 我在 design 里写「可直接复用」，
   读代码发现它是**群成员专用**（硬编码「添加/移除群员」+ `submitData` 直接调 `addOrRemoveGroupUser`），
   于是新建纯选人组件 `ContactPicker.vue`；`ChannelContextUtils` 我写「可复用 INIT 已加载的 UserInfo」，
   读代码发现它**根本不持有**缓存对象，于是改为「单次主键查询，频率极低不值得加缓存」。
   两处都在写代码前被纠正，避免了基于错误假设施工。

## 二、问题

1. **我漏改了 Mapper XML，被活体冒烟抓出来**。只在 PO/VO 加了 4 个字段，
   忘了 `UserInfoMapper.xml` 的 `resultMap` / `base_column_list` / `updateByUserId`，
   于是 SET 子句生成空串 → `UPDATE user_info  where user_id=?` → 全部正常路径 1002。
   **这是本批最实质的问题**：单测 17 例全绿、静态门禁全过、`check-api-contract` 0 孤儿，
   唯一抓到它的是活体冒烟。Service 层单测的 mock 掉 Mapper，天然看不见 XML 少一个 `<if>`。
2. **`ChannelContextUtils` 的 2 个方法没有单测**。tasks 原计划建 `ChannelContextUtilsTest`（≥5 例），
   我排期时判断「逻辑简单」就跳过了。结果 WS 侧的关键行为（关闭后不广播、推 27 帧抹除）
   **只靠 GUI 验证**——而 GUI 恰恰是沙箱里做不了的。等于这一块既无单测又无活体验证。
3. **PO 的 Java 字段初始值是有害的**（TDD 抓出）。我给 `onlineStatusVisible = 1`、`momentVisibility = 0`
   设了初始值，看起来无害，实际会让 `new UserInfo()` 天然带值，
   而 `updateByUserId` 的 `<if test="bean.xxx != null">` 会把它们一并写入 SQL。
   上线后表现为「改朋友圈隐私 → 在线状态开关被重置」，且**极难定位**（不在你改的那个方法里）。
4. **本轮 session 太长，前端部分压缩了验证**。Privacy.vue（200+ 行）、ContactPicker.vue、
   PublishMoment.vue 改造、UserInfo.vue 清理、路由重定向——写完了但只做了 build + eslint error 数对账，
   **没有逐个交互自查**。「build 通过」和「功能正确」之间还有一段 GUI 验证。
5. **CRLF 告警第三次**。+113 条 `prettier: Delete ␍`。prettier 无 `endOfLine` 配置 + git `autocrlf`，
   三批下来累计几百条同类噪音，已经影响我对「新增多少 warning」这个信号的判读能力。

## 三、原因

1. **Mapper XML 是「加了 DB 列」这件事的第二落点，但我只在 design 的后端改动表里列了 PO/VO/Service/Controller**，
   漏了 XML 这行。design 模板的「Mapper / SQL」行我照抄了「数据访问」四个字，没展开「XML 的三处都要改」。
2. 排期时把「逻辑简单」等同于「不必测」。实际上 WS 帧推送是**跨进程契约**（服务端帧号 ↔ 客户端 case），
   一旦对不上就是「不崩但功能静默失效」——恰恰最需要测试兜底的地方。
3. 上一批（黑名单）给 PO 加字段时没设初始值，没踩到这个坑，这次顺手「加上默认值」反而踩了。
   说明「看起来无害的便利写法」在这个项目的 MyBatis `<if>` 模式下是反模式。
4. 连续多批交付，session 很长，前端环节倾向于「写完 + build 过就算完」。
5. prettier 与 git 的换行约定始终没统一，一直靠人肉忽略。

## 四、改进方案

| 方案 | 落点 | 优先级 |
|------|------|--------|
| **补 `ChannelContextUtilsTest`**：关闭不广播 / 开启正常广播 / 27 帧结构 / 仅推在线好友 / 查不到用户按展示处理 | `src/test`，≥5 例 | **立即**（本批唯一没测的核心逻辑） |
| **硬纪律：任何「DB 加列」任务，design 的后端改动表必须逐项列出 XML 三处**（`resultMap` / `base_column_list` / 目标 `<update|insert>` 的 `<if>`），并把「XML 已改」写进 DoD 勾选项 | `AGENTS.md` §6.4 或 DoD 模板 | **立即** |
| **硬纪律：PO 新字段一律不设 Java 字段初始值**，默认值只由 DDL 承担（`<if test!=null>` 会把初始值写进 SQL） | 写进 `AGENTS.md` §3 附近或 `CONTRIBUTING.md` | **立即** |
| WS 帧号与客户端 `case` 的**双向对账脚本**（扫 `MessageTypeEnum` 帧号 vs `wsClient.js` case 号，报缺失/多余） | `scripts/verify/verify_ws_frame_parity.mjs` + CI | 高（把「漏加 case」从静默失效变成阻断） |
| 统一换行：prettier 加 `endOfLine` + `.gitattributes` 对齐 | `.prettierrc.yaml` / `.gitattributes` | 中 |
| `ChannelContextUtils` 体积已大（600+ 行），WS 相关逻辑应抽出独立组件类 | 独立 L2 重构 | 中 |
| 前端补 vitest（至少覆盖 `ContactPicker` 的名单解析、`Privacy.vue` 的 joinType 回滚） | 批次 5 | 中 |
| 2②-C 候选：屏蔽某人（不看其朋友圈）——`moment_visibility` 体系已就位，缺的只是「用户级屏蔽名单」与判定联动 | 独立 L3 | 中 |

## 五、给下一批的经验

- **加了 DB 列 ≠ 改完了**。列名要出现在**三处**：DDL / 迁移 / Mapper XML（三处都改才算完）。
  只改前两处 = 全部写操作 1002。
- **Service 层单测有结构性盲区**：它看不见 Mapper XML。凡是「问题出在 SQL 生成」的 bug，
  单测再厚也抓不到，必须活体。
- **PO 字段初始值 + MyBatis `<if>` = 串列 bug**。默认值是 DDL 的职责，不是 PO 的。
- **「逻辑简单」不等于「不必测」**。跨进程契约（WS 帧号 ↔ 客户端 case）错位的后果是静默失效，
  是最需要测试的形态。
- **写 design 时逐条核实前提**。本批两处「前提」在读代码后被推翻（UserSelect 不可复用、
  ChannelContextUtils 无缓存），避免了基于错误假设写代码。
- **ADR 要挑那种能收窄爆炸半径的决策**。ADR-001（不动 `canView`）的价值不在于技术正确，
  而在于它让「出问题时要排查多少地方」从「全部朋友圈判定」变成「两个 setter」。
