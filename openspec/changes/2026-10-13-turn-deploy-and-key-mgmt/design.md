# Design — TURN 中继部署与密钥管理

- 关联 Proposal: `2026-10-13-turn-deploy-and-key-mgmt/proposal.md`
- 创建日期: 2026-10-13
- 效率等级: **L4**

## 1. 现状事实（2026-10-13 实测，非推断）

| 事实 | 证据 |
|---|---|
| `docker-compose.yml` **无 coturn service** | 实际 service 列表 = `mysql, redis, migrate, backend`（volume 另计） |
| `CallService#buildIceServers()` 硬编码 STUN | `CallService.java:403` `stun.put("urls", "stun:stun.l.google.com:19302")` |
| 前端**另有一份**同样的硬编码 | `utils/WebRTC.js:9` `const defaultIce = [{ urls: 'stun:stun.l.google.com:19302' }]` |
| 三项 TURN 配置以 `@Value` 静态注入 | `CallService.java:56-61`，无 TTL / 无轮换 |
| prod 三项留空 | `application-prod.properties:46-48` = `${EASYCHAT_TURN_URL:}` 等 |
| 门禁已覆盖「prod 不含公共凭据 / dev 含公共 TURN」 | `verify_no_hardcoded_secret.mjs:106,127,134` |
| **ICE 失败兜底完全未实现** | 渲染层搜不到 `iceconnectionstate` / `oniceconnectionstatechange`（0 命中），而 `specs/voice-call/spec.md:93-96` 已要求该行为 |
| 本机 Docker daemon **未运行** | 前序 Change 已实测排除 Testcontainers 同因 |

## 2. 架构决策（ADR）

### ADR-001：TURN 凭据用 coturn `use-auth-secret` 的 HMAC 时效凭据，**不用**长期静态账号

**决策**：后端按 `<timestamp>:<userId>` 作为 username、`base64(HMAC-SHA1(staticAuthSecret, "<timestamp>:<userId>"))` 作为 credential，随信令引导帧下发。TTL 由 `easychat.turn.ttl-seconds` 控制。

**✅ 已由官方文档证实（T1，2026-10-13 查 coturn `README.turnserver` 原文）**：

> `temporary-username = "timestamp" + ":" + "username"`（timestamp 即 `time(NULL)` 的秒值）
> `temporary-password = base64_encode(hmac-sha1(shared-secret, temporary-username))`

`--use-auth-secret` 的官方定义与之一致：`turn user -> usercombo`、`turn password -> base64(hmac(input_buffer = usercombo, key = shared-secret))`。
分隔符由 `-C, --rest-api-separator` 控制，**默认值就是 `:`** —— 故本设计不新增配置项，直接用默认。

**理由**：静态凭据一旦从 `.env`、容器环境变量或进程环境泄漏，攻击者即可**长期**盗用中继带宽（按流量计费的真实成本）。时效凭据把泄漏窗口压到 TTL 量级，且**同一时刻可签发不同凭据**，便于按用户撤销。

**➕ 查证时额外发现（提升本 Change 价值）**：`--static-auth-secret` 的官方说明明确写「**Multiple shared secrets can be used**（both in the database and in the "static" fashion）」。
→ coturn **原生支持多个共享密钥并存**，故共享密钥**可轮换且无需停机**（新旧密钥同时有效）。这把「密钥管理」从「轮换需停机」降级为「轮换需双密钥并存窗口」。后端只需支持**逗号分隔的多密钥**（T9 追加该断言）。

**⚠ 诚实标注的未知项（T1 未解决的部分）**：coturn 对时间戳的**容忍窗口具体数值，官方 README 未给出**（原文只说「only temporary, while the timestamp is not expired」，语义留给部署方）。故：
- `ttl-seconds` 默认取 **3600** —— 依据是 README 提到其自带示例 `examples/scripts/restapi/shared_secret_maintainer.pl` 是「**每小时**生成新共享密钥」，**小时**为官方示例的轮换单位；
- 但「coturn 侧接受窗口 > 1 小时」这一点**属未验证**，登记为 T9 实测项与 QA 未运行项，**不假装已确定**；
- 缓解：`ttl-seconds` 可下调；置 0 即回落静态（ADR-002）。

**为何不做 coturn 自己的 REST API（`/v1/turn/...`）**：那需要 coturn 暴露 HTTP 端口给应用去调，等于给中继服务再开一个攻击面并引入网络依赖；本应用已在服务端持有 secret，直接本地签发更简单且不新增运行时依赖（符合 `voice-call` 「不新增端口 / 运行时依赖」的既有约束）。

**⚠ 诚实标注的未知项**：coturn 在 `use-auth-secret` 模式下**自身还有一道时间窗校验**（实现细节随版本变化，本次未读 coturn 源码确认其默认窗口）。故 `ttl-seconds` **必须显著短于 coturn 的容忍窗口**，否则客户端会算出 coturn 判为过期的凭据而拒绝。→ 登记为**需实测确认项**，见 §4 待确认与 tasks T9；不假装已确定。

### ADR-002：静态三项**保留**为显式降级，且**使用即告警**

**决策**：`easychat.turn.static-secret` 为空时，回落到既有 `username`/`credential` 静态模式；此时启动落 `WARN` 日志明确「正在使用长期静态 TURN 凭据，泄漏窗口无上限」。

**理由**：不破坏既有部署（很多环境仍在用静态三元组），且把「不安全但可用」变成**显式可见**而不是静默。直接删掉静态模式会是一次破坏性变更，超出本 Change 范围。

### ADR-003：STUN 由服务端下发，前端**不再持有任何公共 STUN 地址**

**决策**：`easychat.turn.stun-url`（默认空）随 `iceServers` 一起下发；前端 `WebRTC.js` 的 `defaultIce` 硬编码删除，`iceServers` 缺省时用**空数组**（而不是回落到公共 STUN）。

**理由**：现状是前后端**各硬编码一份相同地址**，双份维护、必然漂移，且依赖境外公共服务（可达性不可控）。空数组时 `RTCPeerConnection` 仍可工作于同网段/多数 NAT，只是少了 STUN 打洞——这与「服务端已下发 TURN」的生产形态不冲突。

**风险与缓解**：若某部署完全没配 STUN 也没配 TURN，连通性会比现状差。缓解：启动时对「STUN 与 TURN **均**为空」落 `WARN`，并在门禁中断言 prod 模板不得同时留空（见 T7）。

### ADR-004：ICE 失败提示的判据取 `iceconnectionstate === 'failed'`，**不**加固定超时

**决策**：监听每个 `RTCPeerConnection` 的 `iceconnectionstate`；进入 `failed` 时，`useCallStore` 置终态 `failed` 并由 `CallWindow.vue` 展示「当前网络环境不支持通话，请改用语音消息」，同时清理 peer 与本地流。

**理由**：固定超时（如 10s 未连通就判失败）会**误杀**慢网络；而 `failed` 是 WebRTC 标准终态，语义精确。已有 `callFrameCore.mjs` 纯核心的状态机，按既有约定**判定必须落在纯核心**并配门禁 + 变异脚本，不允许把 `if (state === 'failed')` 内联回组件（这正是 `at_mention_core` 踩过的坑）。

**为何复用 `verify_call_core.mjs` / `verify_call_store_core.mjs`**：这两道门禁已存在且已接 CI，新增断言与变异条目即可，无需新造门禁脚本。

### ADR-005：coturn 部署物用**模板 + 环境变量注入**，仓库内**不写任何真实域名/IP/证书路径**

**决策**：`deploy/coturn/turnserver.conf.template` 中 `listening-port` / `realm` / `external-ip` / `cert` / `pkey` 一律留空并注明「须注入」，compose 的 coturn service 用 `${COTURN_*}` 环境变量。

**理由**：仓库是公开的，任何写死的地址都会变成「公开可用的中继配置」，而这正是 2026-10-02 配置外置要消灭的那类问题。

## 3. 关键实现细节

### 3.1 凭据签发（Java）

```java
String username = timestamp + ":" + userId;                 // 例：1757650000:U01234567890
Mac mac = Mac.getInstance("HmacSHA1");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
String credential = Base64.getEncoder()
        .encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
```

⚠ **必须走 `try/finally` 关闭线程池**：`Mac` 实例非线程安全且 `CallService` 是单例 Bean，故**每次签发新建**，不得缓存为字段。

### 3.2 coturn 必备配置（**均据官方 README 原文**，写进模板注释）

**T1 查证时纠正了我原设计的两处疏漏 —— 以下两项是 WebRTC 场景的硬要求，缺了就不工作：**

| 项 | 官方原文依据 | 我原设计的状态 |
|---|---|---|
| `lt-cred-mech` | 「WebRTC uses long-term authentication mechanism, so you have to use `-a` option (or `--lt-cred-mech`). WebRTC relaying will **not** work with anonymous access.」 | ❌ **漏了** |
| `realm` | 「With `-a` option, **do not forget to set the default realm** (`-r` option).」 | ❌ **漏了** |
| `use-auth-secret` + `static-auth-secret` | ADR-001 | ✅ 已设计 |
| `fingerprinting` | 「Usually WebRTC uses fingerprinting (`-f`).」 | 建议开 |

**另三项高频易错（写进模板注释）：**

- **`external-ip`**：服务器在 NAT/容器后**必须**设 `<公网IP>/<内网IP>`。官方原文：「For that 'external' IP, NAT must forward ports directly (relayed port 12345 must be always mapped to the same 'external' port 12345)」。**漏设是「配了 TURN 却仍不通」的头号原因。**
- **端口段**：`--min-port` 默认 49152 / `--max-port` 默认 65535（RFC5766），该段**必须在安全组放行**，否则只有 Allocate 成功、没有实际媒体流。
- **回环/多播**：`--allow-loopback-peers` 官方注明「In production it adds a possible security vulnerability」，故**默认不开启**（`--no-multicast-peers` 同理，禁 224.0.0.0+ 与 FFXX:*）。

**凭据只在会话建立时校验**（官方原文：「once the temporary username and password are obtained by the client application, then the rest is just 'classic' long-term credentials mechanism」；且「the password… is never expiring. So, if the session is properly maintained, it may go forever」）。
→ 即 **TTL 约束的是「新分配」而非「已建立会话」**。这对设计的影响：TTL 应 ≥ 一次通话的最长时长，否则长通话在中途重连时会拿到已过期凭据。**本仓 `VOICE_MAX_DURATION_SECONDS = 60`（语音），故 3600s TTL 远大于之** —— 该约束已核对。

## 3.5 实施期偏离记录（写在这里而非事后遗忘）

**偏离 1：C4 的断言未复用 `verify_call_core.mjs`，改为新建 `verify_turn_config.mjs`。**
原计划（tasks T12）写的是「补断言到既有通话门禁」。实施时发现 `verify_call_core.mjs` **第 35 行自身**
就含 `const ICE = [{ urls: 'stun:stun.l.google.com:19302' }]` 作为 ICE 测试夹具 —— 把
「源码不得含硬编码 STUN」放进去，该门禁会**匹配到自己的夹具并永久报红**。
这与 AGENTS §2.1 第 7 条记载的坑同源（`verify_local_db_core.mjs` 曾把自己的注释判成违规）。
故新建专用门禁并把扫描范围**刻意限定在 `easychat-front/src` 与 `easychat-java/src/main/java`**。

**偏离 2：多密钥（`parseSecrets`）已在 T9 落地，但后端按第一个密钥签发。**
这是刻意的：coturn 侧新旧密钥**都**被接受，用哪个签都有效；只有当配置只剩一个密钥时，
签发结果才会随之改变。故此处无需「多签轮询」逻辑 —— 该复杂化留给真正需要时。

## 4. 数据影响

**无**。不改表、不改字段、不写迁移。`call_log` 不动。

## 5. 依赖

- **不新增生产依赖**：`HmacSHA1`/`Base64` 均属 JDK 标准库；coturn 为**部署侧容器**，不进入后端运行时依赖树。
- 前端不新增依赖。

## 6. 风险与回退

| 风险 | 缓解 | 回退 |
|---|---|---|
| coturn 配错导致「配了但不生效」 | 模板注释标注三大易错项 + 启动自检 + healthcheck | 停掉 coturn service，后端自动回落 |
| 时效凭据 TTL 设得比 coturn 窗口长 → 全员通话失败 | T9 实测确认 coturn 容忍窗口后再定默认值 | `ttl-seconds` 置 0 即回落静态（ADR-002） |
| 前端去掉公共 STUN 兜底 → 未配置 TURN 的环境连通性变差 | 启动 `WARN` + 门禁断言 prod 不得双空 | `easychat.turn.stun-url` 显式配置 |
| 新增容器增加运维面 | compose 内注释 + README 补部署章节 | 删 service 即可 |

**回退总成本**：删掉新增配置项 + 还原 2 处前端 hunk。**无数据迁移、无协议变更**，故回退不需要 openspec 逆向变更。

## 7. 待确认项（不假装已验证）

1. ~~coturn 对时效凭据的时间容忍窗口~~ → **T1 已查证：官方 README 未给出数值**。默认 TTL 取 3600（依据官方示例的每小时轮换），但「coturn 侧接受窗口 > 1h」**仍未验证**，转入 T9 实测 + QA 未运行项。
   > 附：曾尝试的查证路径与失败原因 —— Debian manpage `turnserver.conf(5)` **是 2017 年版，完全不含 `use-auth-secret`**，不可作依据；GitHub raw 域返回 502、code search 需鉴权。**结论：不可拿 2017 年的 manpage 当证据。**
2. **coturn 部署在本机无法实跑**（Docker daemon 未运行）→ 登记为未运行项，不以 YAML 解析通过冒充「部署可用」。
3. **真实对称 NAT 下 TURN 是否真的打通** —— 沙箱无 GUI / 无双实例 / 无可控 NAT，沿用 `2026-09-26-voice-call` 的既有结论。本 Change 不改变这一点，但**新增的自检与告警应使其「可发现」**，这正是 C3 的价值所在。
