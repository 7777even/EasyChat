# Spec Delta — TURN 中继部署与密钥管理

- 关联 Tasks: `2026-10-13-turn-deploy-and-key-mgmt/tasks.md`
- 创建日期: 2026-10-13

## ADDED Requirements

### Requirement: 自建 TURN 中继部署物随仓库交付（turn-deployment-artifact）— C1

系统必须随仓库交付一套**可直接注入环境变量即用**的 coturn 部署物，运维无需从零理解 coturn 的易错项；仓库内**不得**出现任何真实域名 / IP / 证书路径。

#### Scenario: 依赖编排中含 coturn 服务

- **WHEN** 运维执行 `docker compose up -d`
- **THEN** coturn 服务被启动，且与既有 `mysql / redis / migrate / backend` 并列
- **AND** 该服务具备 healthcheck（能区分「进程在」与「端口可用」）

#### Scenario: 仓库内不写死任何中继地址

- **WHEN** 检视 `docker-compose.yml` 与 coturn 配置模板
- **THEN** `listening-port` 之外的 `realm` / `external-ip` / `cert` / `pkey` 均为**空或占位**
- **AND** 门禁断言这些键**不得**出现非空字面量

#### Scenario: 易错项被显式标注

- **WHEN** 运维阅读模板注释
- **THEN** 至少标注三项高频错误：`external-ip` 漏设、端口段（49152-65535/udp）未放行、多播穿透

---

### Requirement: TURN 凭据为时效凭据，静态模式保留但须告警（turn-ephemeral-credential）— C2

系统下发给客户端的 TURN 凭据 SHALL 为 HMAC 签名的时效凭据；长期静态凭据仅作为**显式降级**保留，且**一旦被使用即产生告警**。

#### Scenario: 时效凭据签发

- **WHEN** 服务端为某用户签发 TURN 凭据
- **THEN** `username` 形如 `<unix时间戳>:<userId>`
- **AND** `credential` = `base64(HMAC-SHA1(staticAuthSecret, "<unix时间戳>:<userId>"))`
- **AND** 该凭据在 TTL（`easychat.turn.ttl-seconds`）后失效

#### Scenario: 未配置共享密钥时回落静态且告警

- **WHEN** `easychat.turn.static-secret` 为空而 `username`/`credential` 非空
- **THEN** 下发静态凭据（保持既有部署可用）
- **AND** **同时**产生 `WARN` 级日志，指明「正在使用长期静态凭据，泄漏窗口无上限」

#### Scenario: TTL 置零即回落

- **WHEN** `easychat.turn.ttl-seconds` 配置为 0
- **THEN** 不签发时效凭据，回落 ADR-002 的静态模式
- **AND** 回落同样伴随该 `WARN` 日志（不得静默）

#### Scenario: 配置不全不得静默

- **WHEN** `easychat.turn.url` 非空而 `username` 或 `credential` 缺失
- **THEN** 产生 `WARN` 级日志指出缺失项
- **AND** 下发的 `iceServers` 中该 TURN 条目**不含**半截凭据（不得只给 urls 却缺认证字段）

---

### Requirement: TURN / STUN 配置缺失与 ICE 协商失败必须可观测（turn-degradation-observable）— C3

降级到纯 STUN、或 ICE 协商彻底失败，SHALL 既产生服务端日志，也向用户给出明确提示，**不得**表现为「接通后无声」。

> 本 Requirement 同时**补齐 `specs/voice-call` 中自 2026-09-26 起写下却从未实现的 `Scenario: 连接失败兜底`**。

#### Scenario: 服务端未配置任何中继时告警

- **WHEN** 服务端启动时 `stun-url` 与 `turn.url` **均为空**
- **THEN** 产生 `WARN` 级日志，指明对称 NAT 下通话将不可用

#### Scenario: ICE 协商失败时提示用户

- **WHEN** 任一 `RTCPeerConnection` 的 `iceconnectionstate` 进入 `failed`
- **THEN** 前端将通话置为终态 `failed`，并提示「当前网络环境不支持通话，请改用语音消息」
- **AND** 清理该 peer 与本地采集流（不残留麦克风/摄像头占用）

#### Scenario: 失败判定不得误杀正常通话

- **WHEN** `iceconnectionstate` 为 `connected` 或 `completed`
- **THEN** **不得**判为失败，通话继续
- **AND** 判定逻辑 SHALL 位于纯核心并接受变异检验，**禁止**在组件内联

---

### Requirement: STUN 地址随信令下发，前后端不各持一份（turn-stun-externalized）— C4

STUN 地址 SHALL 由服务端随 `iceServers` 下发，前端**不得**持有任何硬编码的公共 STUN 地址。

#### Scenario: STUN 条目由配置决定

- **WHEN** `easychat.turn.stun-url` 非空
- **THEN** `iceServers` 中包含该 STUN 条目
- **AND** 当其为空时，**不产出** STUN 条目

#### Scenario: 前端兜底不含公共地址

- **WHEN** 服务端未下发 `iceServers`（缺省为空）
- **THEN** 前端以**空数组**创建 `RTCPeerConnection`
- **AND** 源码中不得再出现硬编码公共 STUN 地址（由门禁断言）

---

## MODIFIED Requirements

### Requirement: 信令中继、TURN 与失败兜底（voice-call-signaling-relay）— C3

**变更前**（`specs/voice-call/spec.md:83-96` 原文）：

> #### Scenario: TURN 配置下发
> - **WHEN** 服务端 `application.properties` 配置 `easychat.turn.*`
> - **THEN** 引导帧（`call_invite`/`call_accept`）携带 `iceServers`（STUN+TURN 凭据），客户端据此配置 `RTCPeerConnection`；前端源码不含 TURN 凭据

**变更后**：

> #### Scenario: TURN 配置下发
> - **WHEN** 服务端在 **`application-dev.properties` / `application-prod.properties`**（**非** 公共基线 `application.properties`）配置 `easychat.turn.*`
> - **THEN** 引导帧（`call_invite`/`call_accept`）携带 `iceServers`（STUN+TURN 凭据），客户端据此配置 `RTCPeerConnection`；前端源码不含 TURN 凭据
> - **AND** TURN 凭据为**时效凭据**或**静态凭据**，二者形态由 `easychat.turn.static-secret` 是否配置决定

> **订正理由**：2026-10-02 的配置外置（L4，`archive/2026-10-02-config-externalization`）已把 TURN 三项移入 profile 文件，`application.properties` 第 121~122 行明写「TURN 属环境专属项，只存在于 profile 文件」。原规格文本**指向了已不含该配置的公共基线**，属过期描述。

---

## REMOVED Requirements

**无。**

> 说明：原计划中「prod 留空降级纯 STUN 视为预期行为」这一**注释性说法**被本 Change 订正（见 ADDED 的 C3），但它**不是**一条 Requirement，故不列入 REMOVED。
> ⚠ 诚实标注：`docs/system-facts.md` 与 `application-prod.properties` 注释中「预期降级，非故障」的措辞，须在本 Change 的 T19/T21 一并订正；**若只改规格不改注释，台账仍会继续写着那句错误判断**。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 部署物就位 | ADDED: 自建 TURN 中继部署物随仓库交付 | T3, T4, T5, T6 |
| C2 密钥时效化 | ADDED: TURN 凭据为时效凭据，静态模式保留但须告警 | T7, T8, T9, T10 |
| C3 降级可观测 | ADDED: TURN / STUN 配置缺失与 ICE 协商失败必须可观测 | T7, T13, T14, T15 |
| C4 配置外置去硬编码 | ADDED: STUN 地址随信令下发，前后端不各持一份 | T11, T12 |
| 已有 voice-call-signaling-relay | MODIFIED: 信令中继、TURN 与失败兜底（订正过时的配置来源与凭据形态） | T9, T21 |
| 补既有欠账（proposal R1） | ADDED 的 C3 覆盖 `specs/voice-call` 未实现的「连接失败兜底」 | T13, T14 |
