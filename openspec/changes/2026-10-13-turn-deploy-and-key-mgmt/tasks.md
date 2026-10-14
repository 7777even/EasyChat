# Tasks — TURN 中继部署与密钥管理

- 关联 Design: `2026-10-13-turn-deploy-and-key-mgmt/design.md`
- 创建日期: 2026-10-13
- 预估总工时: **13h**
- 效率等级: **L4**（改部署基线 + 生产配置基线 + 凭据签发）

> 单条 ≤2h。`[TDD]` 标记的必须先写失败测试。
> ⚠ 阶段一是**可行性验证**：若 T1 的 coturn 机制无法在本机确认，须按 T2 的分支处理，**不得**带着未知继续写实现。

## 阶段一：可行性验证（先证伪，再动手）

- [x] **T1** 核实 coturn `use-auth-secret` 的凭据格式与**时间容忍窗口**（design §7-1）。
      **结论（据 coturn 官方 `README.turnserver` 原文，非记忆）**：
      ✅ 凭据格式**已证实** —— `temporary-username = "timestamp" + ":" + "username"`，
      `temporary-password = base64_encode(hmac-sha1(shared-secret, temporary-username))`；
      分隔符 `--rest-api-separator` **默认即 `:`**，无需新增配置。
      ✅ **额外查到两项原设计漏掉的 WebRTC 硬要求**：`lt-cred-mech`（缺了 WebRTC 中继直接不工作）、
      `realm`（官方原文「With -a option, do not forget to set the default realm」）。
      ✅ **额外查到** `--static-auth-secret` 支持**多个共享密钥并存** → 密钥可**不停机轮换**。
      ⚠ **未解决**：coturn 对时间戳的**容忍窗口无数值**（原文仅「while the timestamp is not expired」）。
      ⚠ 查证过程中**踩过的坑**：Debian `turnserver.conf(5)` manpage 是 **2017 年版、不含 `use-auth-secret`**，
      GitHub raw 域 502、code search 需鉴权 —— **已明确拒绝拿过期 manpage 当证据** — ≤1h
- [x] **T2** 依 T1 结论定 `ttl-seconds` 默认值 = **3600**（依据：coturn 自带示例
      `shared_secret_maintainer.pl` 为「每小时生成新共享密钥」，小时为官方示例的轮换单位）。
      同时确认「置 0 即回落静态」降级路径为 ADR-002。
      ⚠ 「coturn 侧接受窗口 > 1h」**仍未验证**，已转入 T9 实测项 + QA「未运行项」，**不假装已确定**。
      附：另核对到 TTL 只需 > 单次通话最长时长（凭据仅在**会话建立时**校验），本仓
      `VOICE_MAX_DURATION_SECONDS = 60` ≪ 3600，该约束满足 — ≤30min

## 阶段二：C1 部署物就位

- [x] **T3** 新增 `deploy/coturn/turnserver.conf.template`：端口段 / realm / external-ip /
      cert / pkey **全部留空并注释说明**（仓库公开，不得写死任何真实地址）。
      ✅ 已含 T1 查证出的 WebRTC 两项硬要求（`lt-cred-mech` / `realm`）+ `use-auth-secret`
      + `no-loopback-peers`（官方注明生产环境开启属安全漏洞）+ 末尾「上线前自检清单」7 条 — ≤1h
- [x] **T4** `docker-compose.yml` 新增 `coturn` service（healthcheck、`extra_hosts`、
      `${COTURN_*}` 注入、**中继端口段整段映射**）。
      ✅ 实测：YAML 可解析、services = `mysql, redis, coturn, migrate, backend`、4 条端口映射；
      **`backend` 刻意不 `depends_on` coturn**（已断言为 false —— 中继故障不应阻断后端启动），
      既有「backend 禁止 --scale」注释原样保留 — ≤1h
- [x] **T5** `.env.example` 的 TURN 段从注释态改为**启用态占位**（`CHANGE_ME`），
      与 `verify_no_hardcoded_secret.mjs` 对 `.env` 的既有约定一致。
      ✅ **先核实该门禁只查 `.env.example` 存在性、不扫其内容**（第 197~201 行），
      故加占位值不会被自家门禁拦下；新增 `EASYCHAT_TURN_STATIC_SECRET` / `_TTL_SECONDS` /
      `_STUN_URL` 与 `COTURN_*` 五项 — ≤30min
- [x] **T6** README 补 §4.6 coturn 部署章节（两种凭据模式 + 三项必查 + **诚实标注本机无法验证**），
      并订正 §4.4 表格与 §7 中「`application.properties` 的 `easychat.turn.*`」的**过时表述**
      （实际在 dev profile） — ≤1h

## 阶段三：C2 密钥时效化

- [x] **T7** **[TDD]** `CallService` 启动自检：`STUN`/`TURN` **双空**、`TURN 配置不全`、
      `使用长期静态凭据` 三种情况各落**不同级别**日志；先写失败测试。
      ✅ 先写测试（12 例）→ 红阶段 `Tests run: 12, Errors: 12`（全是桩）→ 填实现 → **12/12 绿**。
      ✅ `CallService` 加 `@PostConstruct logTurnConfigStatus()` 接线到纯核心的 `assess()`。
      ⚠ **本仓是 JUnit 4**：初版误用 JUnit5，实测 `Tests run: 0 … No tests were executed!`
      —— **编译通过但根本不执行**，若不核对就是一份静默零覆盖的测试
      （根因：pom 同时引入 vintage + jupiter 引擎，surefire 2.22.2 选 JUnit4 provider）
      — ≤1h
- [x] **T8** **[TDD]** 实现时效凭据签发（ADR-001）：先写桩让测试跑红，再填实现；
      钉住「`ttl-seconds` 置 0 回落静态」与「username 形如 `<ts>:<userId>`」。
      ✅ 抽 `TurnCredentialCore` 纯函数（**无状态 / 无 IO / 无 Spring / 不依赖 fastjson**），
      `CallService` 只做 IO 接线。⚠ `Mac` 非线程安全 → **每次新建不缓存**。
      ✅ **变异 5/5 全部捕获**（`Failures` 即转红数）：HMAC 算法改掉→3、Base64 改 URL-safe→3、
      告警级别 warn→info→**1（方向也判得出）**、`parseSecrets` 不剔空项→1、`stunUrls` 留空也产出→2
      — ≤2h
- [x] **T9** **[TDD]** 配置项落地：`application.properties`（**只写 key 声明与注释，不赋值**）、
      `-dev`（静态三元组保留 + 注明会告警）、`-prod`（`static-secret`/`stun-url` **无默认可用值**、
      `ttl-seconds` 默认 3600 并注明**未经真实 coturn 实测**）。
      ✅ 多密钥支持：`parseSecrets` 剔除空项，测试覆盖「`a,,b,` → 2 项」与「不同密钥签出不同凭据」 — ≤1.5h
- [x] **T10** 扩展 `verify_no_hardcoded_secret.mjs`：新增 `static-secret` 无默认可用值断言
      + prod 两个 TURN 键的声明存在性断言。**探针 5/5 全部捕获**，基线字节已还原。
      ⚠ **探针证伪了我自己的初版断言**：初版写成「STUN 与 TURN 不得同时留空」的硬失败，
      却对**正确内容**报红 —— 因为 prod 模板本就刻意留空（部署时注入 env），
      把「部署时是否注入」错当成「仓库内容」约束，CI 会永久红。
      已改为断言**键被声明**（仓库层面真正可验的不变量），并在该处留下注记；
      「部署时至少注入一个」改由应用启动自检 + QA 未运行项覆盖 — ≤1h

## 阶段四：C4 去硬编码

- [x] **T11** **[TDD]** `easychat.turn.stun-url` 外置：`buildIceServers` 的 STUN 条目改为读配置，
      空则**不产出 STUN 条目**（不再回落公共地址）。
      ✅ 实现落在 `TurnCredentialCore.stunUrls/turnUrls` + `CallService` 接线；
      `buildIceServers` 内**不再出现任何 STUN 字面量** — ≤1h
- [x] **T12** **[TDD]** `WebRTC.js` 删除 `defaultIce` 硬编码；`iceServers` 缺省时用**空数组**。
      ✅ **偏离原计划（已记入 design）**：未复用 `verify_call_core.mjs`，改为**新建专用门禁**
      `scripts/verify/verify_turn_config.mjs` —— 原因是 `verify_call_core.mjs` **第 35 行自身**
      就含 `stun:stun.l.google.com:19302` 作为 ICE 夹具，若把「源码不得含硬编码 STUN」放进去，
      会**自匹配永久误报**（同 AGENTS §2.1 第 7 条那类坑）。新门禁扫描范围**刻意排除 `scripts/`**。
      ✅ 含**防空转自检**（正例必须命中 / 注释中的引用与 `127.0.0.1` 必须不命中）。
      ✅ **探针 8/8 全部捕获**（含「换一家公共 STUN」—— 证明判据不是只匹配 google）。
      ✅ 已接 CI `gates` job + 登记 AGENTS §10（**刻意不接 pre-push**：与 `verify_call_core`
      等纯逻辑门禁一致，避免把已 16 道的 hook 再加长） — ≤1h

## 阶段五：C3 降级可观测（补既有规格欠账，见 proposal R1）

- [x] **T13** **[TDD]** `callFrameCore.mjs` 新增「`iceconnectionstate === 'failed'` → 终态 `failed`」
      的**纯判定**；断言必须含**制衡用例**（`connected`/`completed` **不得**被判失败），
      防「恒拒绝」假通过。
      ✅ 红阶段 = 门禁 import 缺导出直接 `SyntaxError`；实现后 **22/22 通过**。
      ✅ 覆盖：failed→`icefailed`+`endCall`+中文提示；**5 个正常 ICE 态一律不判失败**（制衡）；
      **终态不可逆**（5 个终态逐一验）；`idle` 不得被凭空拉进通话；`undefined`/`null` 入参不崩；
      新来电清 `errorMsg`（`startCall`/`accept` 原本已有，**来电路径漏了**） — ≤2h
- [x] **T14** 接入 `useCallStore` / `CallWindow.vue`：`WebRTC` 每个 peer 挂
      `oniceconnectionstatechange` → `useCallStore.onPeerIceState` → 调**纯核心** →
      `endCallLocal` 清理 + 写 `errorMsg`（`CallWindow` 第 20/48 行本就分别渲染
      `endReason`/`errorMsg`，无需改模板）。**判定未内联**（同 `at_mention_core` 教训） — ≤2h
- [x] **T15** 扩展 `mutation_call_core.cjs`：新增缺陷⑪~⑮ 五条，**15/15 全部捕获**。
      ⚠ **发现并修掉一处会让变异全漏网的机制缺陷**：`runGate()` 原本**只跑第一道门禁**，
      而新断言在 `verify_call_ice_fail.mjs` 里 → 那 5 条会全判「漏网」，真因却是
      「门禁没被调用」而非判别力不足。已改为两道门禁**都跑**并把新门禁拷进沙箱 — ≤1h

## 阶段六：验证与收尾

- [ ] **T16** `mvn test` 全量 **905 → 预期增加 N 用例，0 失败**，既有测试零回归 — ≤1h
- [ ] **T17** 前端 `npm run test` 全量 0 失败；`npm run lint` **0 error**（基线 0，不得回退） — ≤30min
- [ ] **T18** 受影响门禁全绿：`verify_no_hardcoded_secret` / `verify_call_core` /
      `verify_call_store_core` / `verify_frontend_lint` / `check-openspec-hygiene`，
      以及 compose 的 **YAML 可解析 + coturn service 存在且无硬编码地址**（新增断言） — ≤1h
- [ ] **T19** `engineering/qa/2026-10-13-turn-deploy-and-key-mgmt.md`：
      ⚠ **必须如实列出未运行项** —— coturn 实跑（无 Docker daemon）、
      真实对称 NAT 连通（无 GUI/双实例）、T1 若未查清则 TTL 默认值待定 — ≤1h
- [ ] **T20** `engineering/retro/2026-10-13-turn-deploy-and-key-mgmt.md` — ≤30min
- [ ] **T21** spec-delta 回写 `openspec/specs/voice-call/spec.md`（含 R2 的**过时 TURN 段落订正**）
      + 新建 `openspec/specs/turn-deployment/spec.md` + `git mv` 归档 — ≤30min

## DoD 自检

- [ ] `tasks.md` 全部勾选（含 T1/T2 的结论分支已有明确答案）
- [ ] `mvn test` 0 失败、既有测试零回归；`npm run test` / `npm run lint` 均 0
- [ ] 新增断言**均已造反例证明有判别力**（T10 / T13 / T15）
- [ ] QA 已落 `engineering/`，且**未运行项如实登记**（不把「YAML 能解析」写成「部署可用」）
- [ ] `docs/system-facts.md` 变更日志已同步；`AGENTS.md` §10 门禁表已同步（如新增门禁项）
- [ ] 归档闭环完成（spec 回写 + `git mv` 到 `archive/`）
