# Design — 接通位置消息(LOCATION 25) 与语音消息(VOICE 24)

- 关联 Proposal: 2026-10-03-location-and-voice-message/proposal.md
- 创建日期: 2026-10-03

## 1. 已确认的人工决策（L4 关卡）

| 决策点 | 选择 | 依据 |
|--------|------|------|
| 语音时长上限 | **60 秒**（对标微信） | 现前端 `MAX_RECORDING_TIME = 60000` **恰好已是 60s**，只需服务端加同值校验防伪造 |
| 音频后缀白名单 | **5 类**：`webm/m4a/mp3/wav/ogg` | 全为媒体容器，无脚本/可执行风险；覆盖 `MediaRecorder` 默认输出 |
| 语音已读红点 | **本次一并做** | 用户明确选择；需 migration-012 加字段 |

> 核实：前端 `MessageSend.vue:692` 的 `MAX_RECORDING_TIME` 已是 `60000`，
> 与微信一致，**前端无需改动**，只在服务端加同值守卫。

## 2. 架构设计

### 2.1 五层断链的修复映射

```
【位置消息 25】                                              【语音消息 24】
                                                          
前端 MessageSend.vue                                        前端 MessageSend.vue sendVoiceMessage
  弹窗 + 经纬度 + extraData JSON                              MediaRecorder → Blob → File
  ✅ 已完整                                              ⚠ 只发消息、**不上传文件**
        │                                                          │
        │                                                          ▼
        ▼                                                     POST /chat/uploadFile
 ① ChatController:72  白名单 {2,5}  ← ① 放行 24/25            （当前完全没调用）
        │                      ────────►                      │
        ▼                                                     ▼
 ② ChatMessageServiceImpl 落库白名单 {1,2,3,5} ← ② 加 24/25   ③ checkFileAllowed
        │                                                      allowedFileTypes 无音频 ← ③ 加 5 类音频
        ▼                                                      │
   INSERT chat_message（duration/file_name/extra_data            ▼
   全都有列，**零 DDL**）                                    file/{YYYYMM}/{messageId}.webm
        │                                                          │
        ▼                                                          │
 ④ messageHandler.sendMessage（已直通）                           │
        │                                                          │
   wsClient switch 无 case 24/25 ← ④ 加 case                     │
        │                                                          │
   Chat.vue:136 分发条件不含 ← ⑤ 加 24/25                        │
        │                                                          │
   ChatMessage.vue 挂组件 ← ⑤ 接 ChatMessageVoice（死组件启用）◄────┘
```

### 2.2 语音已读红点设计（本次新增，唯一需要 DDL 的部分）

**问题**：微信语音未播放时气泡前有个红点，播放后消失。红点是**per-receiver** 状态
（我播没播，与对方无关），所以不能放在 `chat_message` 上。

**方案**：`chat_message_voice_read` 旁挂表

```sql
CREATE TABLE `chat_message_voice_read` (
  `message_id`  BIGINT(20)  NOT NULL COMMENT '语音消息 id（chat_message.message_id）',
  `user_id`     VARCHAR(12) NOT NULL COMMENT '接收方 user_id（播放者）',
  `is_read`     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '0 未播放 1 已播放',
  `read_time`   BIGINT(20)  NULL COMMENT '播放时间（ms）',
  PRIMARY KEY (`message_id`, `user_id`),
  KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**为什么不用加列到 `chat_message`**：`chat_message` 是共享的收发双方消息，
「谁播了」是 per-user 状态，加列会在 A 播放时污染 B 看到的行。
旁挂表是唯一正确形态，也便于将来做「语音未读总数」聚合。

**接口**（2 个新增端点）

| 端点 | Method | 入参 | 出参 | 用途 |
|------|--------|------|------|------|
| `/api/chat/markVoiceRead` | POST | `messageId` | `Result<Void>` | 播放后标记已读 |
| `/api/chat/loadVoiceRead` | POST | `messageIdList`（逗号分隔） | `Result<List<Long>>` | 批量取已读状态（渲染气泡红点用） |

**为什么不做 WS 推送「已读」帧**：微信的红点是「我看到你发的那条」时的标记，
只有播放者自己关心。对方不需要知道"我播了"。因此走 HTTP 拉取即可，不新增帧号
（避免又一次 WS 协议变更，也不需要客户端 case 门禁登记）。

**红点渲染时机**：
1. `Chat.vue` 拿到 `messageList` 后调 `loadVoiceRead` 批量取
2. `ChatMessageVoice.vue` 未播放且不在已读集合 → 显示红点
3. 点击播放 → 乐观清除红点 + 调 `markVoiceRead`；失败则**恢复红点**

### 2.3 语音播放地址

`ChatMessageVoice.vue` 现读 `props.data.filePath`，但 `chat_message` **无 `file_path` 列**，
且 `filePath` 是发送方本地路径（对端无意义）。改走既有下载端点，与 `ShowLocalImage` 同范式：

```
http://localhost:{port}/api/chat/downloadFile?fileId={messageId}&partType=chat&fileType=3&showCover=false
```

> 注意 `downloadFile` 的 `contact_id` 守卫（`ChatMessageServiceImpl:422`）对单聊要求
> `request.userId == message.contactId` —— 接收方下载**对方**发来的消息时
> `contactId` 存的是接收方自己（`sessionId` 由双方 id 拼成，`contactId` 随发送方视角），
> 需实测确认。**若守卫不通过，本项降级为「仅发送方可播放」并在 QA 登记**。

## 3. 接口设计

### 修改（1 个）

`POST /api/chat/sendMessage` 白名单 `{CHAT(2), MEDIA_CHAT(5)}` → 追加 `VOICE(24)`、`LOCATION(25)`。
**契约放宽**（原先非法变合法），出参不变。新增守卫：

| 守卫 | 码 |
|------|----|
| `messageType=24` 但 `fileName` 为空 | `1001` |
| `messageType=24` 但 `duration` 为 null 或 <1 | `1001` |
| `messageType=24` 且 `duration > 60` | `1001`（附「语音不能超过 60 秒」） |
| `messageType=25` 但 `extraData` 非合法 JSON 或缺 `location` | `1001` |
| `extraData` 超 2000（列宽上限） | `1001` |

### 新增（2 个）

见 §2.2。均为 `@GlobalInterceptor`，只允许**接收方**标记（不能替别人标记已读）。

## 4. 数据模型变更

`easychat-migration-012-voice-read.sql`（**幂等**：`CREATE TABLE IF NOT EXISTS`）+ `easychat.sql` 同步。

新建表含 `create_time`？§6.4-5 要求「新建表必须包含 create_time」。
但本表是**状态表**（行随消息创建而增、播放而更新），用 `read_time` 已表达变更时间；
再加 `create_time` 与 `read_time` 语义重叠。此处按 §6.4-5 加 `create_time` 以符合规范，
并在表注释说明 `read_time` 为播放时间、`create_time` 为行创建时间。

## 5. 安全设计

- **上传白名单放宽**：新增 5 类音频后缀。它们是媒体容器，**不可执行**；
  与既有 `DANGEROUS_SUFFIX_LIST`（exe/bat/js/sh/ps1 等 18 项）并存不冲突
- **音频大小上限**：新增 `Constants.AUDIO_SUFFIX_LIST`，在 `checkFileAllowed` 中
  走「音频」分类，取 `sysSetting.maxFileSize`（默认 15MB）。60s 的 `.webm` 约 120–480KB，
  余量充足。**不新增 sysSetting 字段**（避免 DDL 与管理端联动）
- **已读标记越权**：`markVoiceRead` 必须校验「当前用户是该消息的**接收方**」，
  否则任何人可替他人标记已读。判据：`message.sendUserId != tokenUserId` 且用户在该会话中
- **`loadVoiceRead` 越权**：只能查自己所在会话的消息；批量入参限长（≤200 个 id）防滥用
- **`downloadFile`**：沿用既有鉴权，不放宽

## 6. ADR

### ADR-001: 已读状态用旁挂表，不用 `chat_message` 加列

- 状态: 已接受
- 上下文: 方案 A = `chat_message` 加 `is_voice_read` 列；方案 B = 旁挂 `chat_message_voice_read`。
- 决策: **B**。理由：播放状态是 per-receiver 的，A 播放会污染 B 看到的行；
  且一张会话内常有多条语音，A 方案无法表达「哪几条没播」。
- 后果: 正面——语义正确、可加 `(user_id, is_read)` 索引支撑「未读语音数」聚合。
  负面——多一次 JOIN（用 `loadVoiceRead` 批量查规避）；新表需纳入备份与迁移。

### ADR-002: 已读状态走 HTTP 拉取，不新增 WS 帧

- 状态: 已接受
- 上下文: 微信有「XX 撤回了一条消息」这类实时同步，但**红点是私有的**——
  「我播没播」只有播放者关心，对方不需要知道。
- 决策: **不新增帧**，走 `loadVoiceRead` 批量 HTTP 拉取。
- 后果: 正面——零 WS 协议变更（不必登记门禁 `MUST_HANDLE`，旧客户端不受影响）。
  负面：进入会话时才拿到红点，非实时（微信也是进入会话时才显示红点，实测行为一致）。

### ADR-003: 语音格式只支持 5 类容器，不做 amr 转码

- 状态: 已接受（用户已确认）
- 上下文: 微信语音是 `.amr`。若要对标微信需引入 amr 编解码。
- 决策: **只放宽 5 类容器后缀，不做转码**。前端录的是 `.webm`（`MediaRecorder` 默认），
  转码会引入 ffmpeg 依赖与失败路径。
- 后果: 正面——零新依赖，与微信**播放体验**一致（时长 + 点击播放 + 未播红点），
  只是文件格式不同，**用户无感知**（微信里也听不出 amr 与 webm 的差别）。
  负面——无法直接播放微信导出的 `.amr` 文件；导出/迁移到微信不互通（不在本次范围）。

### ADR-004: 服务端加 60s 时长守卫

- 状态: 已接受
- 上下文: 前端 `MAX_RECORDING_TIME` 已是 60000ms，但 `duration` 是客户端传的参数，可伪造。
- 决策: 服务端校验 `0 < duration <= 60`，否则 `1001`。
- 后果: 正面——挡住伪造超长语音（也是存储/带宽滥用的第一道闸）。
  负面——若未来放宽前端上限，需同步改服务端常量（记入 system-facts）。

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| **改 `Chat.vue` / `ChatMessage.vue` 波及全部消息渲染**（文本/媒体/撤回/系统消息） | 中 | **高** | 分发条件用「**追加** 24/25」而非重写；回归必测六类消息（2/5/14/3/8/9/11/12/26）；前端无 vitest，依赖 build + 手工冒烟 |
| **`downloadFile` 的 contact_id 守卫挡住接收方播放** | 中 | 中 | design §2.3 已标注需实测；不通过则降级「仅发送方可播」并在 QA 登记，不为此放宽鉴权 |
| `MediaRecorder` 在部分平台不支持 `.webm` | 低 | 中 | 已有 `onerror` + 提示；Safari 需 `audio/mp4`，此时 5 类白名单含 `m4a` 可兜（但格式串仍需适配） |
| 已读标记越权（替他人标记） | 低 | 中 | 单测断言「非接收方 → 拒绝」 |
| migration-012 未在存量库执行 | 中 | 中 | 冒烟前置检查 + QA 记录实跑两次验证幂等 |
| 新表未纳入数据备份 | 中 | 低 | 备份是整库目录级（`projectFolder`），新表自动覆盖；在 system-facts 记录表归属 |

## 8. 依赖与前提

- 前提（**已核实**）：`chat_message` 已有 `duration` / `file_name` / `file_size` / `file_type` /
  `extra_data(varchar 2000)` 列 → **位置与语音消息本体零 DDL**
- 前提（**已核实**）：前端 `MAX_RECORDING_TIME = 60000` 已是 60s，无需改
- 前提（**已核实**）：`ChatMessageLocation.vue` 代码完整（含高德 URI 跳转），只需接线
- 前提（**已核实**）：`allowedFileTypes` 当前**不含任何音频后缀**，语音上传必被 `CODE_2604` 拒
- 前提（**已核实**）：`Chat.vue:224` 的 `isNormalMessage` **已含 25**，右键菜单逻辑无需改
- 前提：`downloadFile` 对接收方的可达性需实测（见 §2.3）
- 前置：`2026-10-03-ws-frame-parity` 已把 24/25 登记为 `KNOWN_GAP`，本变更接通后须移入 `MUST_HANDLE`
- 无其他 Change 依赖