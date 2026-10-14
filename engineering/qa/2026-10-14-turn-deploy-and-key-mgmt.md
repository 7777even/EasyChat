# QA — TURN 中继部署与密钥管理

- 日期: 2026-10-14
- 效率等级: **L4**（`openspec/changes/2026-10-13-turn-deploy-and-key-mgmt`）
- 范围: 新增 coturn 部署物（`deploy/coturn/` + compose service + `.env` + README）、后端 TURN 凭据由
  长期静态改为 **HMAC 时效凭据**、STUN 去硬编码、通话 **ICE 失败兜底**（补既有规格欠账）、
  2 个新门禁 + 1 套变异。**数据库零变更**。

## 验收口径

- **C1 部署物就位**：仓库自带 coturn 配置，注入环境变量即可起自建中继；仓库内不写死任何真实地址。
- **C2 密钥时效化**：下发的是 `<时间戳>:<用户ID>` + HMAC-SHA1 签名，泄漏窗口 = TTL；
  静态凭据保留为显式降级且**使用即告警**；支持多密钥**不停机轮换**。
- **C3 降级可观测**：三类降级各打日志；ICE 协商失败时**用户收到明确提示**（不再是「接通后无声」）。
- **C4 去硬编码**：STUN 随信令下发，前后端不各持一份；`iceServers` 缺省为空数组。
- **DoD**：917 后端 + 786 前端全绿；新增断言**均经变异/探针证明有判别力**；生产代码零回归。

## 实际执行命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -B test`（**全量**） | **917 通过 / 0 失败 / 0 错误**（既有 905 + 新增 12），`BUILD SUCCESS` |
| `npm run test`（**全量**） | **82 文件 / 786 用例全通过**，exit 0 |
| `npm run lint` | **0 error**（6660 warning），基线 0 未回退 |
| `node scripts/verify/verify_turn_config.mjs` | **32/32**，exit 0（本轮新建门禁）|
| `node scripts/verify/verify_call_ice_fail.mjs` | **22/22**，exit 0（本轮新建门禁）|
| `node scripts/verify/mutation_call_core.cjs` | **15/15 变异全部捕获**（原 10 + 新增 ⑪~⑮），exit 0 |
| `node scripts/verify/verify_no_hardcoded_secret.mjs` | exit 0（含新增 2 条断言）|
| `verify_call_core` / `verify_call_store_core` / `verify_at_mention_core` / `check-openspec-hygiene` | 全 exit 0 |

### 判别力实证（**先造反例，再谈覆盖**）

| 对象 | 结果 |
|------|------|
| `TurnCredentialCore`（12 用例） | **变异 5/5 捕获**：HMAC 算法改掉→3 条转红、Base64 改 URL-safe→3、告警级别 `warn`→`info`→**1（方向也判得出）**、`parseSecrets` 不剔空项→1、`stunUrls` 留空也产出→2 |
| `verify_no_hardcoded_secret` | **探针 5/5 捕获**（裸密钥 / `CHANGE_ME` / 公共中继 / guest-guess / 删注入点）|
| `verify_turn_config` | **探针 9/9 + 8/8 = 17/17 捕获**（含「换一家公共 STUN」「模板删 `lt-cred-mech`」「模板写死 `external-ip`」「compose 删 coturn」「backend 改 depends_on coturn」）|
| `mutation_call_core` | 缺陷⑪~⑮ 全部捕获 |

### 过程中抓到的 4 个真实问题（非「预期」，是查出来的）

1. **JUnit 5 测试在本仓会静默不执行**：pom 同时引入 vintage + jupiter 引擎，surefire 2.22.2 选 JUnit4 provider。
   初版测试表现为 `Tests run: 0 … No tests were executed!` —— **编译通过、BUILD 失败，像是红阶段达成，实为零覆盖**。
2. **变异脚本一度全判「漏网」**：Windows 上 `mvn` 是 `mvn.cmd`，`execFileSync('mvn')` 全部 ENOENT → `exit=null`，
   真因是「命令没启动」而非判别力不足。**这正是上一轮刚在 `mutation_chat_dispatch.cjs` 修掉的同一个坑，自己又踩了一次**。
3. **门禁初版对正确内容报红**：断言「prod STUN/TURN 不得同时留空」，而模板本就刻意留空（部署时注入 env）
   —— 把「部署时有没有注入」错当成「仓库内容」约束，CI 会永久红。已改为断言「键被声明」，并在代码留注记。
4. **新门禁若挂错位置会永久自匹配误报**：`verify_call_core.mjs` 第 35 行自身含 `stun:stun.l.google.com` 夹具。
   故新建专用门禁并把扫描范围排除 `scripts/`（同 AGENTS §2.1 第 7 条那类坑）。
5. **变异脚本会让新变异全漏网**：`mutation_call_core.cjs` 的 `runGate()` 原本只跑第一道门禁，
   新断言在第二道里 → 5 条 ICE 变异会全判漏网。已改为两道门禁都跑 + 新门禁拷进沙箱。

## 未运行项（**如实登记，未以任何形式记作通过**）

1. **coturn 未真实部署** —— 本机 **Docker daemon 未运行**。
   仅验证了：compose **YAML 可解析**、service 与 healthcheck 存在、端口映射 4 条、
   `backend` 不 `depends_on` coturn。**「YAML 能解析」≠「部署可用」**，模板内已写入此警示。
2. **`external-ip` / 防火墙 / 安全组 / 端口段放行四项无法验证** —— 需真实网络环境。
   这四项恰恰是「配了 TURN 却仍不通」的高频原因，已在模板末尾做成 7 条自检清单。
3. **coturn 对时效凭据的时间戳容忍窗口** —— 官方 `README.turnserver` **未给出数值**
   （原文仅「while the timestamp is not expired」）。`ttl-seconds` 默认 3600 依据的是官方示例
   `shared_secret_maintainer.pl` 的「每小时轮换」，**该默认值未经真实 coturn 实测**。
4. **真实对称 NAT 下的媒体连通未验证** —— 沙箱无 GUI / 无双实例 / 无可控 NAT。
   与 `2026-09-26-voice-call` 的既有结论一致；**但本 Change 新增的自检与提示使该问题「可被发现」**，
   这正是 C3 的价值所在 —— 发现能力已具备，**连通性本身仍未证**。
5. **ICE 失败的用户可见效果未做 UI 实测** —— 依前端 AGENTS §4.1，本改动属
   **B 类（判定逻辑修复，同一输入下界面像素**不同**——由「无声」变为「有提示」）**，
   故**判定为需要 A 类证据**（截图/录屏），而沙箱无 GUI 无法产出。
   → **登记为遗留**：发版前须人工补一张 CallWindow 失败态截图，或改判为 B 类并补逐条断言映射。
   ⚠ 我**不**把它写成「已验证」。

## 证据

- 变异与探针均为**脚本化、可复跑**，输出结构含 `[捕获]/[漏网]/[无效]` 三态与汇总行
  （脚本置于会话临时目录，未入库 —— 入库会与 §10 门禁表重复维护）。
- 单测 12 条 + 门禁 32 条 + 22 条断言均在仓库内，可随时复跑。

## 结论

**达成**。C1–C4 全部落地并有判别力证据；遗留 #32 式的「规格写了但没实现」欠账（`voice-call` 的
`Scenario: 连接失败兜底`）已**行为层面**闭环。

**建议另立 Change 的遗留**（本 Change 只登记，未越界处理）：

1. `RedisConfig#redissonClient()` catch 类型不匹配（`io.lettuce` vs `org.redisson.client`）。
2. `TokenUserInfoDto.admin` 为 null 时守卫拆箱 NPE → 落到 `CODE_1002` 而非 `1003`。
3. `CallService` 房间注册表仍为进程内 `ConcurrentHashMap`，后端**不可多实例**（compose 注释已保留告警）。
4. 限流与业务校验共用 `CODE_1001`，「按错误码判断成因」的排查手法失效。
5. 上述第 5 条未运行项：ICE 失败态的 UI 截图证据待补。
