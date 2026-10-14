# Spec — TURN 中继部署与密钥管理

> 本 spec 记录**部署与凭据管理能力**，通话协议本身见 `specs/voice-call`。
> 来源：`openspec/archive/2026-10-13-turn-deploy-and-key-mgmt/spec-delta.md`

## ADDED Requirements

### Requirement: 自建 TURN 中继部署物随仓库交付（turn-deployment-artifact）

系统必须随仓库交付一套**注入环境变量即用**的 coturn 部署配置；仓库内**不得**出现任何真实域名 / IP / 证书路径。

#### Scenario: 编排中含 coturn 服务

- **WHEN** 运维执行 `docker compose up -d`
- **THEN** coturn 服务与其余服务并列启动，且具备 healthcheck
- **AND** **后端不 `depends_on` coturn** —— 中继故障不得阻断后端启动，只降级通话

#### Scenario: 模板含 WebRTC 场景的硬性配置

- **WHEN** 检视 `deploy/coturn/turnserver.conf.template`
- **THEN** 必须含 `lt-cred-mech`（缺则 WebRTC 中继**完全不工作**）、`realm`（`lt-cred-mech` 的前提）、
      `use-auth-secret`
- **AND** **不得**开启 `allow-loopback-peers`（官方注明生产环境属安全漏洞）

#### Scenario: 仓库内不写死任何中继地址

- **WHEN** 检视模板与 compose
- **THEN** `external-ip` / `cert` / `pkey` 均为空或环境变量占位
- **AND** 门禁断言这些键**不得**出现非空字面量，compose 内**不得**出现公共中继地址

#### Scenario: 易错项被显式标注

- **WHEN** 运维阅读模板注释
- **THEN** 至少标注：`external-ip` 漏设、端口段（49152-65535/udp）未放行、`realm` 留空；
      并附「上线前自检清单」
- **AND** 清单须声明：本机无法验证其中多项，请勿以模板可解析认定部署可用

---

### Requirement: TURN 凭据为时效凭据，静态模式保留但须告警（turn-ephemeral-credential）

系统下发给客户端的 TURN 凭据 SHALL 为 HMAC 签名的时效凭据；长期静态凭据仅作**显式降级**，且**一旦使用即告警**。

#### Scenario: 时效凭据签发

- **WHEN** 服务端为某用户签发 TURN 凭据
- **THEN** `username` 形如 `<unix时间戳>:<用户ID>`
- **AND** `credential` = `base64(HMAC-SHA1(staticAuthSecret, "<unix时间戳>:<用户ID>"))`，**标准** Base64
- **AND** 该凭据在 TTL（`easychat.turn.ttl-seconds`，默认 3600）后失效

#### Scenario: 支持多密钥以便不停机轮换

- **WHEN** `easychat.turn.static-secret` 配置为逗号分隔的多个密钥
- **THEN** 空白项被剔除，每个密钥都能签发出与自身匹配的凭据
- **AND** coturn 侧新旧密钥**同时接受**（官方：Multiple shared secrets can be used），故轮换**无需停机**

#### Scenario: 未配置共享密钥时回落静态且告警

- **WHEN** `static-secret` 为空（或 TTL ≤ 0）而 `username`/`credential` 非空
- **THEN** 下发静态凭据（保持既有部署可用），并产生 `WARN` 日志指明「泄漏窗口无上限」

#### Scenario: 配置不全不得静默

- **WHEN** `turn.url` 非空却没有任何可用凭据
- **THEN** 产生 `WARN` 指出问题
- **AND** 下发的 TURN 条目**不含**半截凭据

#### Scenario: 启动期自检覆盖三类降级

- **WHEN** 应用启动
- **THEN** 「STUN 与 TURN 双空」「TURN 凭据不全」「使用长期静态凭据」各打 `WARN`，
      仅「配全且用时效凭据」为 `info`
- **AND** 双空告警文案须点明后果：**对称 NAT 下将接通后无声**

---

### Requirement: TURN / STUN 配置缺失与 ICE 协商失败必须可观测（turn-degradation-observable）

降级 SHALL 既产生服务端日志，也向用户给出明确提示，**不得**表现为「接通后无声」。

#### Scenario: 服务端未配置任何中继时告警

- **WHEN** 启动时 `stun-url` 与 `turn.url` **均为空**
- **THEN** 产生 `WARN` 级日志，指明对称 NAT 下通话将不可用

#### Scenario: ICE 协商失败时提示用户

- **WHEN** 任一 `RTCPeerConnection` 的 `iceconnectionstate` 进入 `failed`
- **THEN** 前端将通话置为终态 `icefailed`，提示「当前网络环境不支持通话，请改用语音消息」
- **AND** 清理该 peer 与本地采集流（不残留麦克风/摄像头占用）

#### Scenario: 失败判定不得误杀正常通话

- **WHEN** `iceconnectionstate` 为 `connected` / `completed` / `checking` / `new` / `disconnected`
- **THEN** **不得**判为失败，通话继续
- **AND** 判定逻辑位于纯核心（`callFrameCore#reduceIceConnectionState`）并接受变异检验，**禁止**在组件内联

#### Scenario: 终态不可逆

- **WHEN** 通话已处于 `ended`/`rejected`/`canceled`/`busy`/`icefailed` 时收到 ICE 失败事件
- **THEN** **不得**改写状态（否则挂断后残留事件会把界面拉回「连接失败」）

#### Scenario: 提示不得残留到下一次通话

- **WHEN** 一通通话因 ICE 失败结束后收到新的来电
- **THEN** `errorMsg` 被清空，上一通的失败提示不得挂在本次来电界面

---

### Requirement: STUN 地址随信令下发，前后端不各持一份（turn-stun-externalized）

STUN 地址 SHALL 由服务端随 `iceServers` 下发，前端**不得**持有任何硬编码的公共 STUN 地址。

#### Scenario: STUN 条目由配置决定

- **WHEN** `easychat.turn.stun-url` 非空
- **THEN** `iceServers` 中包含该条目；为空时**不产出** STUN 条目

#### Scenario: 前端兜底不含公共地址

- **WHEN** 服务端未下发 `iceServers`
- **THEN** 前端以**空数组**创建 `RTCPeerConnection`，源码中不得出现硬编码公共 STUN 地址
  （判定 `stun:` + 非 localhost，**不只匹配某一家的公共 STUN**）

#### Scenario: 门禁须防空转

- **WHEN** 运行配置外置门禁
- **THEN** 含正例（构造含硬编码 STUN 的源码，必须被命中）与反例
  （注释中的引用、`127.0.0.1` 自建调试，必须**不**命中）

---

## 追溯

| Proposal Capability | Requirement | Task |
|---------------------|-------------|------|
| C1 部署物就位 | turn-deployment-artifact | T3, T4, T5, T6 |
| C2 密钥时效化 | turn-ephemeral-credential | T7, T8, T9, T10 |
| C3 降级可观测 | turn-degradation-observable | T7, T13, T14, T15 |
| C4 去硬编码 | turn-stun-externalized | T11, T12 |

**已知边界（未决，非缺陷）**：`disconnected` 长时间不恢复这一中间态**不会**触发提示 ——
判据刻意取 `failed` 而非固定超时（后者会误杀慢网络）。
