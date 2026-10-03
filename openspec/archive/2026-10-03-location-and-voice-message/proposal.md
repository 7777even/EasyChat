# Proposal — 接通位置消息(LOCATION 25) 与语音消息(VOICE 24)

- 创建日期: 2026-10-03
- 效率等级: **L4**（改业务能力 + 改 WS 帧契约 + 放宽上传白名单）

## Why

`scripts/verify/verify_ws_frame_parity.mjs` 于 2026-10-03 首次运行时报出：
`LOCATION(25)` 与 `VOICE(24)` 两个帧**既不在服务端落库白名单、客户端也无 case**。
追查后用实测取证（`scripts/smoke/probe_location_voice.py`，9/9 断言命中）确认：
**这两个功能从未真正可用过**，断链比预想更深——共五层。

DB 实证（决定性证据）：

```
SELECT message_type, COUNT(*) FROM chat_message GROUP BY message_type;
  1  2
  2  6
```

**0 条 24 / 25 记录**。历史上没有任何一条位置消息或语音消息被成功发出过。
这也解释了为什么既有 spec 验收与常规冒烟都没发现——**没人成功用过，功能自然无从验收**。

五层断链（按请求流向）：

| # | 层 | 位置 | 现状 | 后果 |
|---|-----|------|------|------|
| 1 | **发送白名单** | `ChatController:72` | `ArrayUtils.contains({CHAT(2), MEDIA_CHAT(5)}, messageType)` | 点「发送位置」/「按住说话」→ 直接 `CODE_1001 请求参数错误`，**连消息都建不出来** |
| 2 | **落库白名单** | `ChatMessageServiceImpl:235-240` | `ArraysUtil.contains({CHAT, GROUP_CREATE, ADD_FRIEND, MEDIA_CHAT}, ...)` | 即使过了第 1 层也不写 `chat_message` 表 |
| 3 | **文件落盘** | `ChatMessageServiceImpl:322` `checkFileAllowed` | `allowedFileTypes=jpg,jpeg,...,txt,mp4,mp3` —— **不含任何音频后缀** | 语音的 `.webm` 上传直接 `CODE_2604 类型不支持` |
| 4 | **客户端 case** | `wsClient.js` switch | 无 `case 24` / `case 25` | 对端实时收不到（**不崩不报错**） |
| 5 | **渲染分发** | `Chat.vue:136` | `1 \|\| 2 \|\| 5 \|\| 14 \|\| 20`，不含 24/25 | 即使历史拉取到了也不渲染；`ChatMessageVoice.vue` 是**从未被 import 的死组件** |

已有的「半成品」反而加重了迷惑性：
- `ChatMessageLocation.vue` **代码完整可用**（含经纬度详情 + 高德 URI 跳转），但因第 4/5 层断链**从未被渲染过一次**
- `Chat.vue:224` 的 `isNormalMessage` **已含 25**（右键菜单认它），却因第 5 层断链永不执行
- `MessageSend.vue:660-680` 的位置发送逻辑完整（弹窗 + 经纬度 + `extraData` JSON），却因第 1 层被拒

## What Changes

- **服务端**（3 处白名单 + 1 处配置 + 1 张新表）
  - `ChatController#sendMessage` 放行 `VOICE(24)` / `LOCATION(25)`，并补各自的必填守卫（60s 上限等）
  - `ChatMessageServiceImpl` 落库白名单加 24/25
  - `allowedFileTypes` 默认值追加音频后缀（`webm,m4a,wav,ogg`），新增 `Constants.AUDIO_SUFFIX_LIST`
    供 `checkFileAllowed` 分类大小上限
  - `globalSearch` 的 `messageTypeList` 加 24/25（位置消息的 `messageContent` 是地址，应可被搜到）
  - **新建表 `chat_message_voice_read`**（语音未播放红点，per-receiver 状态）→ migration-012
  - **新增 2 端点**：`markVoiceRead` / `loadVoiceRead`
- **前端**（3 处接线 + 1 个死组件启用 + 1 处补上传）
  - `wsClient.js` 加 `case 24` / `case 25`（走真实消息路径：落本地库 + `reciveMessage`）
  - `Chat.vue:136` 分发条件**追加** 24/25
  - `ChatMessage.vue` 接上 `ChatMessageVoice.vue`（**启用死组件**），`isNormalMessage` 追加 24
  - `ChatMessageVoice.vue` 的 `audioSrc` 从 `props.data.filePath` 改走 `/chat/downloadFile`
    （`chat_message` **无 `file_path` 列**，且该值是发送方本地路径、对端无意义），
    并新增**未播放红点**（乐观清除 + 失败恢复）
  - `MessageSend.vue#sendVoiceMessage`：先发消息拿 `messageId`，再走 `/chat/uploadFile` 传 `.webm`
    （当前**只发消息不上传文件**，等于发了个空壳）
- **配套**
  - 门禁 `verify_ws_frame_parity.mjs`：24/25 从 `KNOWN_GAP` 移入 `MUST_HANDLE`

## Capabilities

- C1: 用户可在单聊/群聊**发送并接收位置消息**，含地址、经纬度、在地图中打开
- C2: 用户可**按住说话录制并发送语音消息**，对端可点击播放、显示时长；时长上限 60s
- C3: 位置/语音消息进入历史漫游链路（落库 → 历史拉取 → 渲染），不因刷新/换设备丢失
- C4: 位置消息按地址文本可被全局搜索命中（与微信一致）
- C5: 语音消息在接收方播放前显示**未播放红点**，播放后消失；状态每接收方独立

## Impact

- **对外接口**
  - `POST /api/chat/sendMessage` 放行 2 个 messageType（契约**放宽**，非新增端点）；出参 `Result<MessageSendDto>` 不变
  - **新增 2 个端点**：`POST /api/chat/markVoiceRead`、`POST /api/chat/loadVoiceRead`（语音已读红点）
- **数据结构**：**消息本体零 DDL**（`chat_message` 已有 `duration` / `file_name` / `file_size` /
  `file_type` / `extra_data(2000)`，位置经纬度塞 `extra_data` 足够）；
  **但语音已读红点需 1 张新表** `chat_message_voice_read` → migration-012（详见 design §4）
- **WS 协议**：**帧号不变**（24/25 本就存在于 `MessageTypeEnum`），只补客户端 case → 向后兼容。
  已读状态**不新增帧**（ADR-002：红点是私有状态，对方不关心）
- **上传白名单**：放宽 `allowedFileTypes` 默认值，新增 5 类音频后缀（`webm/m4a/mp3/wav/ogg`）。
  **音频文件**从此可上传 → 属安全面扩大，须与 `DANGEROUS_SUFFIX_LIST`（可执行文件硬拦截）并存，
  且音频容器不可执行，风险可控
- **存量数据**：无。库里 0 条 24/25，本次只改「以后能不能发」
- **回归面**：`Chat.vue` / `ChatMessage.vue` 是聊天主渲染路径（**所有消息都过**），
  改动需重点回归**文本(2)/媒体(5)/撤回(14)/系统消息(3,8,9,11,12,26)** 六类渲染不被带偏
- **回退**：消息本体部分为白名单与条件放开，`git revert` 即回退；
  新表 `chat_message_voice_read` 可保留不影响旧版本（旧代码不读它）

---

## ☑ 人工确认关卡

> 本提案经 **用户（项目 owner）** 于 **2026-10-03** 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
>
> 三个显式决策（用户已拍板，详见 design §1）：
> 1. **语音时长上限 = 60 秒**（对标微信）。核实发现前端 `MAX_RECORDING_TIME` 已是 `60000`，
>    **前端无需改动**，只在服务端加同值守卫防伪造
> 2. **音频后缀白名单 = 5 类**：`webm` / `m4a` / `mp3` / `wav` / `ogg`（不加 `amr`，
>    避免为兼容微信导出文件引入转码依赖）
> 3. **语音未播放红点本次一并做**（用户追加，超出「先能用」的最小范围）：
>    需 migration-012 新建旁挂表 `chat_message_voice_read`（ADR-001：per-receiver 状态
>    不能加列到共享的 `chat_message`）