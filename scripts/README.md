# scripts/ — 自动化门禁脚本

本目录存放对齐 `AGENTS.md` §10 的机控脚本。规范靠人遵守会有漂移；脚本则把关键纪律翻译成"不通过就失败"的硬闸门。

## 脚本清单

> **真源是 `AGENTS.md` §10 的门禁表**（含每条阻断条件）。本表只作「有哪些文件、在哪跑」的索引，
> 两者不一致时以 §10 为准。2026-10-06 订正：此前本表只收录 11 个，而仓库实际有 21 个门禁脚本。

| 脚本 | 触发时机 | 守门内容 |
|------|----------|----------|
| `commit-msg-lint.mjs` | git hook `commit-msg` | 提交格式 `type(scope): 描述`、type / scope 枚举、描述含中文、禁止 body |
| `pre-commit-guard.mjs` | git hook `pre-commit` | 暂存区黑名单（构建产物、日志、临时文件）；QA 证据附件除外 |
| `check-api-contract.mjs` | git hook `pre-push` / CI | 后端 Controller 路由 vs 前端 `Api.js` 调用，找出孤儿路由 / 潜在漂移 |
| `check-openspec-hygiene.mjs` | git hook `pre-push` / CI | 进行中的 Change 四件套齐全、tasks.md 全勾但未归档阻断、archive 内存在未勾选任务阻断 |
| `check-ipc-registration.mjs` | git hook `pre-push` / CI | `ipc.js` 导出与 `index.js` 调用不匹配（漏注册即静默失效） |
| `verify/verify_no_hardcoded_secret.mjs` | git hook `pre-push` / CI | 配置基线含裸凭据、prod profile 含公共 TURN 凭据或 DB 默认可用密码、`.env` 入库 |
| `verify/verify_mapper_params.mjs` | CI | Mapper XML 占位符与方法签名不匹配（写错运行期才抛 `BindingException`） |
| `verify/verify_sql_concat_guard.mjs` | git hook `pre-push` / CI | Mapper XML 出现 `${}` 字符串拼接（违反 §6.2-3）；排序白名单 `SortOption` ↔ XML `<when>` 分支**双向对账**（防「枚举加了分支没加 → 该排序项静默失效落到 `<otherwise>`」）；分支 SQL 与枚举声明须逐字一致；分支不串表；裸字面量排序必须在白名单内 |
| `verify/verify_ws_frame_parity.mjs` | git hook `pre-push` / CI | WS 帧号两端对账：漂移 / 重复 / 空洞 / 落库帧无 case / 新增帧未声明意图 / `KNOWN_GAP` 过期 |
| `verify/verify_file_type_content_type.mjs` | git hook `pre-push` / CI | `FILE_TYPE_CONTENT_TYPE` 覆盖前端在用 `fileType`（缺失 → `undefined<ext>`、静默失败）；MIME 前缀须以 `/` 结尾；语音 `fileType=3` 须为 `audio/*` |
| `verify/verify_password_session.mjs` | git hook `pre-push` / CI | 改密 / 找回密码后未吊销全部端 Token、未推 `FORCE_OFF_LINE`；验证码交给 logger；未登录端点限流存在「token 缺失直接 return」早退；邮件未配置未 fail-closed |
| `verify/verify_password_handoff.mjs` | CI | 密码明文交接红线：客户端哈希密码、MD5 存量双验证被移除、`isBCrypt` 与 `matches` 口径分叉 |
| `verify/verify_audit_and_at_all.mjs` | git hook `pre-push` / CI | `recordLog` 未自动补齐客户端 IP；`@所有人` 权限仅在客户端生效 |
| `verify/verify_schema_drift.mjs` | git hook `pre-push` / CI（**独立 job + MySQL service**） | 基线 ⇄ 活库表结构漂移、`user_info.password` 列宽 ≥60、解析器静默漏表、迁移编号缺口。**唯一需要活库**，fail-closed |
| `verify/verify_migration_flyway.mjs` | git hook `pre-push` / CI | 结构性 DDL 未被存在性探针守卫、`flyway-core` 版本被改回、迁移未打包、`baseline-version` 与最大迁移号不等、含 `DELIMITER` / `CREATE PROCEDURE` |
| `verify/verify_virtual_core.mjs` | CI | 虚拟滚动算法（`virtualListCore.mjs`）偏移表二分定位、高度/偏移一致性、越界索引 |
| `verify/verify_call_core.mjs` | CI | 通话帧核心（`callFrameCore.mjs`）帧→状态转移与 `MessageTypeEnum` 的一致性 |
| `verify/verify_call_store_core.mjs` | CI | 通话 store 编排（`callStoreCore.mjs`）：1800ms 复位守卫通话身份校验、`endReason` 清空、补丁无遗漏/越界、store 未复用纯核心 |
| `verify/verify_local_db_core.mjs` | CI | 本地 SQLite（`dbSqlCore.mjs`）where 真值过滤、字段丢弃可见化、空 set/where 短路、`add column` 幂等 |
| `verify/verify_chat_message_dispatch.mjs` | CI | `Chat.vue` 分发条件覆盖后端落库白名单、子组件未 import（死组件）、纯文本兜底抢分支 |
| `verify/verify_frontend_test_base.mjs` | CI | 测试依赖钉死版本、`vite` 仍 4.x、生产依赖不混入测试框架、`vitest.config.mjs` 挂 `@vitejs/plugin-vue`、全局桩含 `ResizeObserver`、`@` 别名两处一致、CI 跑 `npm run test` |
| `verify/verify_frontend_lint.mjs` | git hook `pre-push` / CI | `lint` 不得带 `--fix`、eslint 现代解析目标、已修缺陷复发（`new Promise(async` 等）、error 数不超基线 |
| `verify/verify_packaging_config.mjs` | CI | 打包配置唯一真源（yml 与 `package.json.build` 不得并存 —— 后者存在时前者**静默失效**）；`npmRebuild` 必须显式声明；`files` 必须排除开发文件与重复资源，且**不得误排运行期必需文件**。`--selftest` 与 electron-builder 真实 `FileMatcher` 对拍（exit 2 = SKIP，**SKIP ≠ 通过**） |
| `verify/verify_export_chat_core.mjs` | CI | 导出纯逻辑（`exportChatCore.mjs`）`csvCell` 前置单引号防护、TXT/CSV 字段错位 |
| `verify/verify_at_mention_core.mjs` | CI | 群聊 @ 提及判定（`atMentionCore.mjs`）搜索/显示口径一致、`atAll` 叠加角色权限、组件未把判定内联回去 |
| `verify/verify_mutation_scripts.mjs` | CI **独立 job**（不接 pre-push） | 跑全部 9 个 `mutation_*.{cjs,mjs}`，任一非 0 退出即阻断；区分「脚本判失败」与「脚本起不来」；依赖缺失报 SKIP 并写明原因 |
| `migrate/preflight-baseline-check.mjs` | 启动前（人工 / 运维执行） | 「声称自己是最新版」却结构不符时拒绝启动（`SPRING_FLYWAY_BASELINE_VERSION` 的人工声明入口） |
| `setup-git-hooks.mjs` | 一键安装脚本 | 把 hook 类脚本注册到 `.git/hooks/` |

`verify/` 下另有 9 个 `mutation_*.cjs` / `mutation_*.mjs`（反向验证脚本）——
2026-10-06 起由 `verify_mutation_scripts.mjs` 在 **CI 独立 job** 中统一驱动，见文末「变异检验」。

## 安装

```bash
node scripts/setup-git-hooks.mjs
```

安装后：
- `git commit` → 自动触发 commit-msg-lint + pre-commit-guard
- `git push`  → 自动触发 check-openspec-hygiene（全勾未归档、或已归档但未全勾，均拒绝推送）

## 跳过（慎用）

紧急修复时如需临时跳过 hook：`git commit --no-verify`。
跳过即视为主动豁免，AI 不得自主建议 `--no-verify`。

## 纯脚本组合（不装 hook）

```bash
node scripts/commit-msg-lint.mjs /path/to/commit-msg-file
node scripts/pre-commit-guard.mjs
node scripts/check-api-contract.mjs [--strict]
node scripts/check-openspec-hygiene.mjs [--strict]
```

## --strict 模式

`check-api-contract.mjs --strict` 与 `check-openspec-hygiene.mjs --strict`：
将 WARN 级别也升级为 exit 1，用于 CI 门禁收紧。

## WS 帧协议对账（`verify_ws_frame_parity.mjs`）

WS 帧号是服务端 `MessageTypeEnum` ↔ 客户端 `wsClient.js` `case` 的**跨进程契约**。
两端错位后的表现是「不崩但功能静默失效」——这是最难靠人工回归发现的一类问题，
2026-10-02 本门禁首次运行即抓出位置消息（25）与语音消息（24）端到端未接通。

它自动从服务端源码解析**落库白名单**（`ChatMessageServiceImpl` 的 `ArraysUtil.contains(new Integer[]{...})`），
不需要人工维护第二份清单。每个帧必须显式声明意图：

| 表 | 含义 | 加入条件 |
|----|------|---------|
| `MUST_HANDLE` | 需客户端实时处理，缺 case 即阻断 | 推送帧（聊天/控制/信令） |
| `INTERNAL_FRAMES` | 服务端内部帧或请求帧，客户端**不得**有 case | 投递前被改写（如 13）；渲染→服务端请求（如 23） |
| `KNOWN_GAP` | 已知「功能未接通」，每次运行都打印保持技术债可见 | 既不落库也无 case 的业务帧 |

新增帧号却未声明 → **阻断**。已登记的 `KNOWN_GAP` 若后来被接通 → **阻断**（提示删除登记）。

## 变异检验（配套脚本，反向验证）

「脚本存在」不等于「门禁有判别力」。`verify/` 下 9 个 `mutation_*.{cjs,mjs}` 做的是**反向**验证：
故意把被测代码改回缺陷实现，确认对应门禁**真的会转红**。

```bash
node scripts/verify/mutation_at_mention_core.mjs        #  9 条变异
node scripts/verify/mutation_call_store_core.cjs        # 11 条变异
node scripts/verify/mutation_channel_online_status.cjs  #  8 条变异，每次跑一次 mvn test（约 2–3 分钟）
node scripts/verify/mutation_chat_dispatch.cjs          #  9 条变异，每次跑一次 npm run test
node scripts/verify/mutation_local_db_core.cjs          # 12 条变异
node scripts/verify/mutation_migration_flyway.cjs       # 14 条变异
node scripts/verify/mutation_password_session.cjs       # 12 条变异
node scripts/verify/mutation_schema_drift.cjs           #  5 条变异
node scripts/verify/mutation_ws_frame_parity.cjs        #  9 条变异，改 3 个源文件
```

**为什么原本不进 CI**：每次运行都要起子进程、跑门禁甚至跑 `mvn test`，耗时数十秒到数分钟。
代价是**会静默腐烂，且腐烂时不会有人知道**。

> **2026-10-06 已改**：新增 `verify_mutation_scripts.mjs` 作为 **CI 独立 job** 统一驱动这 9 个脚本
> （闭环遗留 #27）。**刻意不接 `pre-push`** —— 推送前的快速闸门不该等 3~6 分钟。
> 该门禁区分「脚本判失败」与「脚本起不来」（`exit=null` 是环境问题，不是漏网），
> 且依赖缺失时报 **SKIP 并写明原因**（`SKIP ≠ 通过`）。

### 五条纪律

1. **锚点会随源码漂移**。变异脚本用**手写源码片段**作锚点，被测源码一改就失配。
   `[FAIL] 锚点未命中` / `[无效]` 说明失配（脚本已判失败）；
   但**更危险的是「锚点命中却只覆盖了部分片段」**——变异看起来生效了，实际没改变要测的行为，
   门禁会 exit 0 → 报 `[MISSED]`。此时不要怀疑门禁，**先怀疑自己的锚点**。

2. **锚点必须对换行不敏感**。仓库文件多为 **CRLF**（Windows checkout），锚点里写 `\n`
   会命中 0 次 → 全部变异静默空转，而汇总行照样显示「N/N 捕获」。
   正确做法：匹配前把内容归一化为 LF（`src.replace(/\r\n/g, '\n')`），
   写回时按原风格还原 EOL。**已在本仓形成两套标准实现**：
   `mutation_migration_flyway.cjs` 的 `toLf()`、`mutation_at_mention_core.mjs` 的 `edit(file, findLf, replLf)`。

3. **两个脚本会临时改写源文件再还原**，因此带前置守卫：目标文件有未提交改动时
   直接 `exit 2` 拒绝执行（还原会覆盖你的工作）。
4. **`[SKIP]` / `[无效]`（锚点未命中）与 `exit === null`（命令启动失败）一律判失败**。
   只看汇总的 `[CAUGHT]` 会被这些假通过骗过去——首版就因
   Windows 上 `execFileSync('mvn')` 跑不了 `.cmd`（ENOENT → `status=null`）
   而误报「全部捕获」。
5. **改了被测核心就必须重跑对应变异脚本**。这是本节最重要的一条：变异脚本不在 CI 里，
   「上次跑过 N/N 全捕获」对今天的源码**不构成任何保证**。

> **实测教训（2026-10-06）**：在一次只读盘点中实跑全部 9 个变异脚本，发现 **2 个已腐烂**——
> `mutation_call_store_core.cjs` 实测 **1/11**（10 条锚点因 CRLF 全部落空）、
> `mutation_local_db_core.cjs` 实测 **11/12**（1 条锚点随 `ADB.js` 重排漂移）。
> 而台账与 QA 记录里写的仍是「11/11」「12/12」。
> **即：变异脚本自身的验证结论会过期，而过期后没有任何机制会提醒。**
> 修复后复跑：**9 个脚本共 89 条变异全部捕获，0 无效、0 漏网、9/9 exit 0**。
> 本次修复内容：① `mutation_call_store_core.cjs` 的 `mutate()` 补 LF 归一化 + 按原风格还原 EOL
> （11/11）；② `mutation_local_db_core.cjs` 重锚那条随 `ADB.js` 重排漂移的用例（12/12）；
> ③ 其余 5 个仍用裸 `src.includes(find)` 的脚本（`schema_drift` / `password_session` /
> `chat_dispatch` / `channel_online_status` / `ws_frame_parity`）**一并补上归一化**，
> 消除同类陷阱；④ `ws_frame_parity` 原先把 `\r\n` **写死在锚点里**，
> 等于反向锁死「目标文件必须是 CRLF」，一并改为双向归一化。
>
> ### 为什么没有做成门禁（一次被否掉的尝试，值得记）
>
> 曾写 `verify_mutation_anchor_safety.mjs`，试图**静态判定**每个变异脚本的锚点匹配
> 是否换行安全。结果在 9 个脚本上产生 **2 类假阳性**并被否掉：
>
> ① **把锚点文本里的 `.includes(` 当成匹配代码** —— 例如锚点字符串
>    `'return name.includes(kw) || uid.includes(kw)'` 本身含有 `.includes(`，
>    正则无法区分「代码在调 includes」与「字符串里写着 includes」。
> ② **数据流推断不可靠** —— `mutation_migration_flyway.cjs` 的归一化写成
>    `new RegExp(escapeRe(find).replace(/\\n/g, '\\r?\\n'))`，而该文件里有多个同名局部变量，
>    「变量 → 赋值表达式」的一跳/两跳追踪会取到**另一个**不含归一化的赋值。
>
> **结论**：跨脚本静态推断另一个脚本的内部数据流，**不足以充当门禁**。
> 宁可只做确定的词法检查（如 `verify_sql_concat_guard.mjs` 扫 `${}`），
> 也不交付一个自己都判不准的断言 —— **报错 ≠ 断言正确**，
> 这是 AGENTS §2.1 第 14 条「断言通过 ≠ 断言在做事」的镜像。
> 「防腐」因此落在纪律（上面第 5 条）而非机控，此为**已知残余风险**。
