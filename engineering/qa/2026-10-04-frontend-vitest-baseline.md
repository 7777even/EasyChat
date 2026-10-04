# QA — 前端测试基线（vitest）

- 关联 Change：`openspec/changes/2026-10-04-frontend-vitest-baseline`（已归档至 `openspec/archive/2026-10-04-frontend-vitest-baseline`）
- 等级：**L3**（新增 devDependency）
- 结论：**通过**

## 一、范围与验收口径

把前端测试覆盖从「仅纯逻辑核心」扩展到「组件层」，闭环 `docs/system-facts.md` §14 遗留 #7 的最后一段。

验收口径：tasks.md 的 T0.1~T0.5、T1.1~T1.4、T2.1~T2.3、T3.1~T3.3、T4.1~T4.4。

## 二、阶段零：最大风险被证伪（先实测再写实现）

引入测试运行器最典型的失败是「装上了，vite 被顺带升级，Electron 构建崩掉」。
故 `npm run build` 是否仍为 0 被列为**第一个**验收项，而不是最后补。

### 基线（T0.1）

| 项 | 基线值 |
|---|---|
| Node | v24.0.2 |
| vite | **4.5.14** |
| electron-vite | 1.0.29 |
| @vitejs/plugin-vue | 4.6.2 |
| `dependencies` | 21 项，sha1 前缀 `ffd67dccd3d93e6a` |

### 装依赖（T0.2）

`npm i -D --save-exact vitest@1.6.0 @vue/test-utils@2.4.6 jsdom@22.1.0`

`added 107 packages, removed 4 packages, and changed 2 packages`——
**「removed/changed」是安装前就记下的疑点**，故 T0.3 专门做了实证。

### 实证结论（T0.3 / T0.4 / T0.5）

| 验收项 | 结果 |
|---|---|
| `vite` 是否被顺带升级 | ✅ 仍为 **4.5.14**（`@vitejs/plugin-vue` 4.6.2、`electron-vite` 1.0.29 亦未变） |
| `npm run build` | ✅ **exit 0**，三个 `built in`（309ms / 10ms / 14.29s），与基线同构 |
| `dependencies` 是否被污染 | ✅ sha1 与基线**逐字节相同**（`ffd67dccd3d93e6a`），21 项不变，无测试框架混入 |
| 版本是否钉死 | ✅ 三个依赖均为精确版本，无 `^` |

**为什么选 vitest 1.6.0 而非最新 5.0.3**：最新版的 peer 要求 vite 5/6，
而项目是 `vite@4.5.14` + `electron-vite@1`。用 `^` 会某天自动升到 2.x 并**连带升级 vite**。
该约束已固化为门禁断言。

**为什么 jsdom 22**：本机 Node v24 而 CI Node 20；jsdom 22 声明 `node>=16`，双端兼容。

## 三、TDD 红阶段（T1.4）

先写**故意失败**的用例（`expect(1).toBe(2)`），实跑确认：

```
[FAIL] _smoke-red.spec.js > smoke: 测试运行器连通性 > 故意失败以验证命令会转红
 _smoke-red.spec.js:7:15
 Test Files  1 failed (1)
 Tests  1 failed (1)
exit=1
```

失败路径精确到行号。**未确认这一步就往下写，等于没验证过测试真的能跑。**
确认后即删除该冒烟用例。

## 四、首批测试锁定的是真实事故点

### 4.1 vitest 组件测试（10 例，`chat-message-inner-dispatch.spec.js`）

锁定的正是 **2026-10-03 死组件事故**：`ChatMessageVoice.vue` 此前从未被 import，
语音消息（24）落进 `v-else-if="data.messageType != 5"` 的纯文本兜底分支，
实际渲染出 `messageContent="[语音]"` 的**普通文本气泡**而非可播放语音条——
服务端正常、历史漫游也拉到，不抛异常不报错。

### 4.2 源码级对账门禁（`verify_chat_message_dispatch.mjs`）

解析 `Chat.vue` 模板取出分发表：

```
ChatMessageTime <- [2, 5]
ChatMessageSys   <- [3, 1, 9, 8, 11, 12, 26]
ChatMessage      <- [1, 2, 5, 14, 20, 24, 25]
```

再与后端 `MessageTypeEnum` + `ChatMessageServiceImpl` 的**落库白名单**（6 项）对账。
落库白名单才是「会进历史漫游、需要渲染」的准确集合——
枚举里 13/15/16/17/18/19/21/22/23/27 等是控制帧，本就不该在消息列表里出现。

**为什么这一层用源码门禁而非 mount 测试**（ADR-001 的补充）：
`Chat.vue` 是 180+ 行视图，挂载需连带 stub Layout、路由、MessageSend、ContextMenu 等十余个依赖，
成本远高于收益，且这些依赖一变测试就假红。而要验的其实是**模板里的条件表达式**，
源码解析更直接、更快、更贴近「条件漏项」这一失败模式本身。
`ChatMessage.vue` 的**二次分发**（24/25 与纯文本兜底的先后关系）才由 vitest 真挂载验证。

## 五、变异检验：5/5 捕获，含死组件事故

| 变异 | 结果 |
|---|---|
| **★ 退回死组件事故**：删掉 Voice 分支，24 落进纯文本兜底 | ✅ 捕获 |
| 位置消息分支被删（25 掉进纯文本） | ✅ 捕获 |
| 语音与位置类型号互换（24/25 写反） | ✅ 捕获 |
| 撤回态条件被删（14 渲染出原文） | ✅ 捕获 |
| 管理员删除态与撤回态文案区分消失 | ✅ 捕获（第 5 条**初版漏网**，补断言后捕获） |

## 六、过程中修正的六处缺陷

### 6.1 漏挂 `@vitejs/plugin-vue`（design.md 漏项）

首次跑测试报：
`Failed to parse source for import analysis because the content contains invalid JS syntax. Install @vitejs/plugin-vue`

vitest **不会**自动启用该插件（它只对 electron-vite 构建生效）。
该包本就是既有 devDependency，**无需新增**。

### 6.2 门禁自身崩溃而非报错

`buildUpdateSql` 类变异让门禁在顶层 `await import` 处抛出，
**后续断言全不执行 → 输出无 `[FAIL]` 行** → 变异脚本只能判「门禁崩溃」，检验作废。
已加 `checkNoThrow` 用 try/catch 把异常转成一条 FAIL。

### 6.3 变异沙箱策略错误（两轮）

初版用 robocopy 复制整个工程到临时目录：

1. **robocopy 退出码语义特殊**：0=无文件复制、1=复制成功、≥8 才错。
   被当成「复制成功=失败」抛出，脚本第一次 reset 就崩。
2. 修掉后**基线自检立刻拦下**：沙箱没有 `node_modules`，`vitest` 命令根本不存在
   → 所有用例都会「红」，那不是判别力而是环境缺失。

改为**原地临时改 + try/finally 必还原**：本项目测试只读被测源码、不写文件，
原地改安全且更快。已验证运行后工作区未被污染。

### 6.4 汇总行把「漏网」算成「捕获」

第 5 个变异漏网时，汇总行仍输出「5/5 全部捕获」，**与全捕获完全无法区分**。
这已是本轮第三次同类问题（`verify_migration_flyway` / `mutation_local_db_core` 各一次）。
汇总改为计入 `[漏网]` 与 `[无效]`。

### 6.5 对账门禁的截断锚点选错

`Chat.vue` 门禁首版用 `indexOf('</template>')` 截取插槽，
而**每个分支自身都以 `</template>` 闭合** → 只截到第 1 个分支 →
误报「24/25 未覆盖」。若当时照单全收，会去「修」一个不存在的缺陷。
改用截到 `</MessageVirtualList>`。

### 6.6 别名一致性正则漏形态

`electron.vite.config.js` 写 `resolve('src/renderer/src')`，
`vitest.config.mjs` 写 `resolve(__dirname, 'src/renderer/src')`。
正则只覆盖单参形态 → 误报「不一致」。已同时覆盖两种。

## 七、回归

| 项 | 结果 |
|---|---|
| `npm run test` | **10/10 PASS**，exit 0 |
| `npm run build` | **exit 0**（三个 `built in`） |
| 门禁 | **19/19 PASS**（新增 `verify_chat_message_dispatch` + `verify_frontend_test_base`） |
| 变异脚本 | **8/8 exit 0**（新增 `mutation_chat_dispatch.cjs`） |
| 工作区 | 运行变异后未被污染（try/finally 生效） |

## 八、已知局限（不谎报为通过）

| 项 | 说明 |
|---|---|
| 组件覆盖仅 1 个视图 | 首批只做 `ChatMessage.vue`（10 例）。`Chat.vue` 走源码门禁而非 mount。**其余组件仍无测试** |
| `Chat.vue` 的 mount 测试未做 | 依赖过多、成本过高（见 §4.2）。**这是有意的取舍，不是遗漏** |
| 快照测试未做 | 快照易碎，且「快照没变」不等于「行为对」 |
| 路由 / 守卫测试未做 | 需更多桩 |
| CI 未实跑 | 本地 `npm run test` / `npm run build` 均已实跑；**GitHub Actions 未实际执行**（沙箱无网络与远端） |
| `npm run lint` 仍失效 | **既有技术债**，与本变更无关。测试命令独立故不叠加 |

## 九、证据文件

| 文件 | 内容 |
|---|---|
| `_apply_adb.py` / `_chat_branches.py` | 调研夹具（非仓库测试） |
| `easychat-front/src/renderer/src/__tests__/chat-message-inner-dispatch.spec.js` | 10 个组件测试用例 |
| `scripts/verify/mutation_chat_dispatch.cjs` | 5 个变异用例 |

## 十、结论

**通过。** 遗留 #7 的最后一段闭环。

但须诚实说明：**这是测试基线，不是测试覆盖**。
目前组件层只有 1 个视图有用例，其余组件仍处「改了只能靠人点」的状态。
真正的收益要在后续持续补测试时才会完全显现——本批的价值是**把这条路铺通并证明它能抓住真缺陷**。