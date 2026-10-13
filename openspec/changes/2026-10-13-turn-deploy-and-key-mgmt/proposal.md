# Proposal — TURN 中继部署与密钥管理

- 创建日期: 2026-10-13
- 效率等级: **L4**（命中 §8「横切配置：部署配置基线」——本变更直接改 `docker-compose.yml` 部署基线与 `application-prod.properties` 生产基线；且涉及凭据签发方式，属安全面）

## Why

通话功能（`voice-call`）自 2026-09-26 归档起就**声称支持 TURN**，但仓库里**没有任何 TURN 的部署物**：唯一可用的是 `application-dev.properties` 里入库的**公共**中继（`turn.anyfirewall.com` + `guest/guess`），而生产模板 `application-prod.properties` 三项**刻意留空**，注释写着「降级纯 STUN，对称 NAT 下通话不通（**预期降级，非故障**）」。

这条「预期非故障」的判断**从用户视角是错的**：TURN 缺失时，对称 NAT 用户**呼叫能建立、能接通，然后无声**（ICE 收集失败），没有任何报错、没有任何提示、没有任何日志。也就是说：系统当前在**静默地**给一部分用户一个坏体验，而运维**无从知晓**发生了。

同时，密钥管理是空白：三项凭据是**长期不变的静态值**，无 TTL、无轮换、无过期；一旦泄漏，攻击者即可长期盗用中继带宽。

## What Changes

**先说结论：本次只做「部署物 + 可观测 + 密钥时效化」，不动通话协议、不动数据库。**

- 后端（`easychat-java`）:
  - `CallService#buildIceServers()`：由「静态三项」改为**支持时效凭据**（`timestamp:username` + HMAC-SHA1 签名），保留静态模式作兼容降级。
  - 新增 TURN 配置的**启动期自检与告警日志**：TURN 未配置 / 配置不完整 / 自签名证书缺失，分别落不同级别日志（不能只留 STUN 就当没事）。
  - `application.properties` / `-prod`：新增 `easychat.turn.static-secret`（HMAC 共享密钥，默认空 → 回落静态凭据）、`easychat.turn.ttl-seconds`；STUN 地址由硬编码改为可外置。
- 部署（仓库根）:
  - `docker-compose.yml` 新增 `coturn` service（含 healthcheck、`extra_hosts: host.docker.internal:host-gateway`、外部 IP 提示）。
  - 新增 `deploy/coturn/turnserver.conf.template`，**关键项留空并注释说明**（不硬编码域名/IP/证书路径）。
  - 新增 `.env.example` 的 TURN 段补全（当前是注释状态）。
- 前端:
  - `utils/WebRTC.js` 的 `defaultIce` 与后端硬编码的 STUN **同值但双份维护**，本改为**仅作最后兜底**且去掉硬编码地址（由后端下发；服务端未下发时用空 `iceServers`，让浏览器不尝试连任何公共 STUN）。
  - `CallWindow.vue` / `useCallStore.js`：ICE 失败（`iceconnectionstate === 'failed'`）时给用户**明确提示**并落日志，而非静默无声。
- 数据库: **无变更**（不动 `call_log`，不新增表）。

### 明确不做（避免范围扩散）

- 不做多实例通话（`CallService` 房间注册表迁 Redis）——独立议题，本次仅在 compose 注释里保留既有告警。
- 不做云端备份、不做 TURN 带宽计费、不做 TURN 集群。

## Capabilities

- **C1（部署物就位）**：仓库自带 coturn 部署配置，运维注入环境变量即可起一套**自建**中继，不需从零理解 coturn 的易错项。
- **C2（密钥时效化）**：TURN 凭据为 **HMAC 时效凭据**（默认 TTL 可配），泄漏的凭据在 TTL 后自动失效；**静态凭据保留为显式降级**，且静态模式被使用时**有告警**。
- **C3（降级可观测）**：TURN 未配置 / 不可用 / ICE 协商失败，三种情况各有**日志**；用户侧在通话失败时**收到明确提示**而非无声。
- **C4（配置外置去硬编码）**：STUN 地址不再在前后端各硬编码一份，改为随信令下发；`verify_no_hardcoded_secret.mjs` 的断言同步扩展到时效密钥。

## Impact

- **对外接口**: **不变**。不新增/删除 HTTP 端点，不新增 WS 帧号，`voice-call` 的 `CALL_*` 帧结构不变（`iceServers` 字段内容变化，但结构兼容）。
- **存量数据**: 无影响，无迁移。
- **性能 / 安全**:
  - 安全 **提升**：凭据从「永久静态」变为「时效签名」，并新增部署物避免误用公共中继（媒体不再经第三方）。
  - 运维面 **新增**：多一个容器需运维；coturn 需开放 3478(udp/tcp) + 49152-65535(udp) 端口段与 TLS 证书。
  - 前端有**可见变化**：ICE 失败会弹提示（属 UI 变更 → 按前端 §4.1 需 A 类证据「页面截图/录屏」**或** B 类「可执行行为证据」，见 tasks）。
- **回退方案**:
  - 后端：新增配置项**全部有默认值**，不注入即回落当前行为（静态三项 / 纯 STUN），删掉 compose 的 coturn service 亦不影响后端运行。
  - 前端：提示为增量 UI，不提示也能通话。
  - **故回退成本 = 删掉新增配置项，无数据/协议迁移**。

---

## ☑ 人工确认关卡

> 本提案经 **用户（本会话）** 于 **2026-10-13** 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [x] 需修改（说明：**Q1 / Q2 / Q3 三个分叉点全部选 A 全做** —— 时效凭据做、STUN 去硬编码做、用户侧 ICE 失败提示做）
> - [ ] 退回重新评估

### 确认后修订（写 design 时核实到的既有事实，须与本 Change 一并闭环）

> 以下三条**不在提案撰写时掌握**，是写 design 阶段读源码/规格后查出的，故在此补记并已并入 C2/C3/C4。

**R1. Q3A 不是新增能力，而是补既有规格欠账。**
`openspec/specs/voice-call/spec.md` 的 `Scenario: 连接失败兜底` 早已写明「前端检测 `RTCPeerConnection` 失败，提示『当前网络/设备不支持，请改用语音消息』，并清理本地资源」。**实测：渲染层搜不到任何 `iceconnectionstate` / `oniceconnectionstatechange` 处理，该 Scenario 从 2026-09-26 归档至今从未实现。**
→ 即 `2026-09-26-voice-call` **带着一条未实现的 Requirement 就归档了**，而当时的 QA 只把「真实媒体链路未验证」记为未运行项，**没有核对规格逐条是否达成**。本 Change 的 C3 因此按「**补实现 + 补判别力证据**」处理，不按新增能力处理。

**R2. `voice-call` 规格的 TURN 段落指向已过时的配置文件。**
`Scenario: TURN 配置下发` 写「服务端 `application.properties` 配置 `easychat.turn.*`」，但 2026-10-02 的配置外置（L4）已把三项移入 `application-dev` / `application-prod`，`application.properties` 第 121~122 行的注释明说「TURN 属环境专属项，只存在于 profile 文件」。规格文本已与现状不符 → 本 Change 在 spec-delta 中一并 **MODIFIED** 订正。

**R3. 「降级纯 STUN 是预期行为」这句在规格层同样是欠账。**
`application-prod.properties` 注释与 `docs/system-facts.md` 都把它写成「预期降级，非故障」。按 R1，连接失败时**既无用户提示也无服务端日志**，故它在事实上就是故障而非降级 → 本 Change 在 spec-delta 中把该行为订正为「**降级必须可观测**」。

---

## 待人工拍板的 3 个分叉点（我已给倾向，但需你定）

> 这三点直接决定工作量与风险面，**不宜由我替你定**。

**Q1. 时效凭据（`use-auth-secret` + HMAC）做不做？**

| 选项 | 说明 | 代价 |
|---|---|---|
| **A（我倾向）做** | coturn `use-auth-secret`，后端按 `timestamp:username` + HMAC-SHA1 签发 | 需后端实现签发（约 1 人日）+ 共享密钥管理；泄露窗口从「永久」缩到 TTL |
| B 不做 | 维持长期静态凭据，只补部署物与告警 | 零后端改动；凭据泄漏仍可长期盗用带宽 |

**Q2. STUN 硬编码去留？**

| 选项 | 说明 | 代价 |
|---|---|---|
| **A（我倾向）去掉** | 后端 `CallService` 与前端 `WebRTC.js` 各硬编码一份 `stun.l.google.com:19302`，双份维护且**依赖境外公共服务** | 改 2 处 + 门禁同步；需保证服务端始终下发 |
| B 保留 | 少动 | 两份硬编码继续漂移的风险留存 |

**Q3. 用户侧 ICE 失败提示做不做？**

| 选项 | 说明 | 代价 |
|---|---|---|
| **A（我倾向）做** | `CallWindow` 在 `iceconnectionstate === 'failed'` 时提示「网络环境不支持通话」 | UI 变更 → 需附证据（截图或行为断言） |
| B 不做 | 只落服务端日志 | 用户仍是「接通后无声」，**问题对我们不可见** |

**补充需你知悉的事实（不必现在决断，但会影响验收）**：
- coturn 的 `external-ip` / 防火墙 / 安全组是**最容易配错且最难自查**的三项 —— 本次会用 healthcheck + 自检脚本覆盖，但**无法在本机模拟**，最终连通性仍需真实部署环境验证（会登记为「未运行项」而非「通过」）。
- 本机 **Docker daemon 未运行**，故 compose 变更**无法本地实跑验证**，只能靠 YAML 解析 + 静态断言。
