# QA — 补齐 HTTP 请求层与主进程 db 层

- 日期: 2026-10-12
- 关联 Change: `openspec/archive/2026-10-12-request-and-db-layer-tests/`
- 效率等级: L3

## 一、范围

| 阶段 | 对象 | 性质 |
|---|---|---|
| A | `easychat-front/src/renderer/src/__tests__/request-layer.spec.js`（**新增**，21 例） | 补测试，**生产代码零改动** |
| B | `easychat-front/src/main/db/dbModelCore.mjs`（**新增**）+ 4 个 Model 接线 + `scripts/verify/verify_db_model_core.mjs`（**新增**，71 项断言） | 抽纯核心 + 补门禁，SQL 逐字搬移 |

## 二、验收口径与实际执行

### 2.1 阶段 A：请求层 21 例

命令：`npx vitest run src/renderer/src/__tests__/request-layer.spec.js`

结果：**21/21 通过**。

覆盖的 Scenario → 断言对应：

| spec-delta Scenario | 用例 |
|---|---|
| 业务错误被吞成 null | T1「★ 业务错误 → request() 返回 null（不是 throw），且弹 body.message」 |
| showError=false 仍吞 null | T1「★ showError=false → 仍吞成 null，但不弹 Toast」 |
| GET/POST 两 catch 独立 | T1「★ GET 分支同样吞成 null」 |
| 成功返回完整 body | T1「成功（code=0）返回完整 body，不弹错误」 |
| code=0 原样返回 | T2「code=0 → 原样返回 body」 |
| 登录过期走 reLogin | T2「★ code=2001 → reject「登录超时」并 send reLogin」 |
| 其他业务错误取 message | T2「业务错误 → reject body.message」 |
| 非 2xx 带 body 按业务错误 | T3「HTTP 403 + code=2003 → 文案取 body.message」 |
| HTTP 401 + 2001 走 reLogin | T2「★ HTTP 401 + body code=2001 → 同样走 reLogin」 |
| 无 body 才报网络异常 | T3「★ 无 body（连不上）→ 才报「网络异常」」 |
| body 为 null 算网络异常 | T3「body 为 null → 网络异常」 |
| blob 错误体是 JSON | T4「★ blob 错误体是 JSON → 按业务错误处理」 |
| blob 错误体是 HTML | T4「blob 错误体是 HTML → 回退「网络异常」，且不抛解析异常」 |
| arraybuffer 经 TextDecoder | T4「arraybuffer 错误体是 ArrayBuffer → 经 TextDecoder 解析」 |
| 解码自身失败须回退 | T4「blob 的 text() 自身 reject → 仍回退网络异常」 |
| errorCallback 恰好一次 | T2「errorCallback 恰好被调用一次」+ T3「errorCallback 在 error 路径也被调用且只一次」 |

补充覆盖（超出 spec-delta，锁定请求封装细节）：token 头、GET 走 `params`、
非 FormData 走 form-urlencoded、FormData 不覆盖 Content-Type。

### 2.2 阶段 B：db 层纯核心 71 项断言

命令：`node scripts/verify/verify_db_model_core.mjs` → **exit 0**。

11 组：chat_message 单条查询 / 搜索条件 / count 与分页 / 未读数自增 / 会话查询与更新 /
动态 SET 列名映射 / 属性白名单 / 端口分配 / LaterHandle / UserSetting / 纯度 / Model 无内联 SQL。

### 2.3 变异验证（**4/4 全部捕获**）

| # | 变异 | 转红断言 |
|---|---|---|
| 1 | `buildUpdateNoReadCount` 参数调序为 `[userId, contactId, noReadCount]` | `★ 参数顺序 [增量, userId, contactId]`（**1 条**） |
| 2 | 会话列表删掉 `and status = 1` | `会话列表同时带 user_id 与 status=1` + `★ 漏掉 status 会把已删除会话列出`（**2 条**） |
| 3 | `buildSessionAttrSet` 的 `!== undefined` 改为真值判断 | `★ noReadCount=0 也生成子句（不得被真值过滤丢弃）` + `三个字段全映射为 snake_case 列名` + `参数顺序与子句顺序一致`（**3 条**） |
| 4 | 在 `ChatSessionUserModel` 内联回一份 SQL | `ChatSessionUserModel.js 无内联 SQL 字面量`（**1 条**） |

每次变异均为**原地临时改 + 跑完立即还原**，未复制工程到临时目录（避免无 `node_modules` 的沙箱空转）。

### 2.4 阶段 A 生产代码零改动

`git diff --stat easychat-front/src/renderer/src/utils/Request.js` → **空**（PASS）。

### 2.5 全量回归

| 项 | 命令 | 结果 |
|---|---|---|
| 前端测试 | `npx vitest run` | **81 spec / 767 例 passed**（本轮前 80 spec / 746 例，+21） |
| 前端构建 | `npm run build` | **exit 0** |
| 前端 lint | `npx eslint src/main/db src/renderer/src/__tests__/request-layer.spec.js` | **0 error**（713 warning，均为既有 prettier 提示） |
| 后端测试 | `mvn test` | **866 例**，Failures 0 / Errors 0，BUILD SUCCESS |
| 新门禁 | `node scripts/verify/verify_db_model_core.mjs` | **exit 0** |
| 门禁接入 | `node scripts/setup-git-hooks.mjs` | exit 0，生成的 `.git/hooks/pre-push` 含新门禁 |
| 变异全量 | `node scripts/verify/verify_mutation_scripts.mjs` | 见 §2.6 |

**门禁接 hook 前已实跑**（AGENTS §2.1 第 1 条：脚本存在 ≠ 门禁有判别力）。

### 2.6 全量变异跑时**顺带查出两处历史遗留缺陷**（不在本 Change 范围内，已当场修并登记台账 #33 / #34）

跑变异全量时 `verify_mutation_scripts.mjs` exit=1。**这不是本轮改动引起的** ——
本会话早期的 #1（限流换 email 维度）、#2（`resetPassword` 入参收敛为 DTO）、
#4（前置闸门抽纯核心）都改动了被变异脚本锚定的源码字面量，锚点随之失配。

| 脚本 | 现象 | 处置 |
|---|---|---|
| `mutation_migration_flyway.cjs` | **exit=1，但汇总行写「14/14 全部捕获」** —— 锚点未命中 ≠ 变异被捕获，脚本却按「已施加」统计 | 锚点更新为 `configuredBaselineVersion(process.env.SPRING_FLYWAY_BASELINE_VERSION, props)` → 该行改锚后 exit 0 |
| `mutation_password_session.cjs` | 变异 5、6「锚点未命中」 | 5 改锚 `checkRateLimit` 尾部三行；6 改锚带 `@Valid UserResetPasswordDTO dto` 且注解含 `rateLimitByParam = "email"` |

**修锚过程中查出一处真实漏网**（台账 #33）：新增「变异 5c —— email 维度取不到值时直接
return」后，`[MISSED]`。原因是 `verify_password_session.mjs` 第 3 节的断言**全部是字面量
存在性**：`IpTools.getClientIp` 还在、`"ip:"` 还在、`"rate_limit:"` 还在 —— 而变异只是让
它们**不可达**，字面量一个字都没动。已补第 3b 节四条**结构化**断言（其中关键一条用
**花括号配平逐字符数 `return` 的嵌套深度 ≥ 2**，不用正则 —— 正则的多行 `^` 匹配在实测中
判不准）。修后 `mutation_password_session.cjs` **13/13 全部捕获**。

### 2.7 变异中断的一次残留（已还原）

首次跑变异全量时被环境终止（退出码 `0x40010004` = `STATUS_CONTROL_C_EXIT`），
`mutation_channel_online_status.cjs` 的 `finally` 未执行 →
`ChannelContextUtils.java` 残留了一处变异（`if (!isOnlineStatusVisible(userId))` → `if (false)`）。
已 `git checkout` 还原并复验。该脚本**自带**「工作区该文件有未提交改动即拒绝执行」的守卫，
所以重跑时 exit=2 而非带着脏工作区继续 —— 守卫按设计生效。

**教训**：`try/finally` 只在**进程正常结束**时可靠；变异脚本是**原地改真实工程**，
被外部终止就会留脏。这已记入台账 #34 的教训段。

## 三、本轮修正的两处自身失误（均在交付前发现并纠正）

1. **假覆盖（阶段 A 初版）**：T1 曾写 `runAndSwallow` 辅助函数，**复刻**了 `request()`
   的 catch 逻辑而非调用它 —— 等于测试自己验证自己。已改为让 mock 的 `instance.post`
   可控 reject，直接走生产代码自身的 `.catch`。
2. **unhandled rejection 污染（阶段 A 初版）**：`code=2001` 那条未接住返回的 rejected
   promise，它在**后续**用例中以「Serialized Error」形式炸出，导致 T5 三条**无关**用例
   报红。差点误判为 T5 自身失败。
3. **门禁汇总行与实扫数不符（阶段 B）**：PowerShell 控制台 GBK 吞掉中文，
   使「71 通过」被读成「1 通过」。改用 `execFileSync` 取 UTF-8 输出后确认
   **71 = 实扫 71**（72 处 `[PASS]` 里有 1 处是汇总行自身的标记）。**不是缺陷，是编码误读**。
4. **台账结构误判**：同样因 `\\\\` 经 PowerShell 变成两字符，误判第 274 行有 6 个未转义
   管道。改写成 `.mjs` 文件校验后确认 **29 条数据行、异常 0 条**。

## 四、未运行 / 不可运行项

| 项 | 原因 |
|---|---|
| CI 首跑结果 | `gh` 未认证，ubuntu/Node 20 上的实际结果**不做推测** |
| 真实后端联调 | 需启动后端 + GUI 环境；本轮为纯逻辑抽离与补测，行为逐字搬移未变 |
| `npm run build` 的安装包产物 | 属独立 job（Windows NSIS），本轮未跑 |

## 五、结论

**通过**。阶段 A 生产代码零改动且 21 例覆盖四条契约（其中两条为 2026-10-04 才补、
此前无任何守护的分支）；阶段 B 抽离为**逐字搬移**，4 处变异全部被门禁精确捕获。