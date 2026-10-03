# capability: 位置消息与语音消息

> 2026-10-03 建立（`openspec/archive/2026-10-03-location-and-voice-message`）。
>
> 本 capability 承接一个**既有缺陷**的修复：`LOCATION(25)` 与 `VOICE(24)` 两个帧
> 早已存在于 `MessageTypeEnum`，但从五层全部断开——DB 中 `chat_message.message_type`
> 仅有 `1 2`，**0 条 24/25 记录**，说明这两个功能从未被真正使用过，
> 因此既有 spec 验收与常规冒烟都未发现。

## 断链原貌（五层，2026-10-03 实测）

| # | 层 | 位置 | 断点 |
|---|-----|------|------|
| 1 | 发送白名单 | `ChatController#sendMessage` | `ArrayUtils.contains({CHAT(2), MEDIA_CHAT(5)}, ...)` → 点发送直接 `CODE_1001` |
| 2 | 落库白名单 | `ChatMessageServiceImpl#saveMessage` | `ArraysUtil.contains({CHAT, GROUP_CREATE, ADD_FRIEND, MEDIA_CHAT}, ...)` → 不写 `chat_message` 表 |
| 3 | 文件落盘 | `ChatMessageServiceImpl#checkFileAllowed` | `allowedFileTypes` 完全不含音频后缀 → `.webm` 上传被 `CODE_2604` 拒 |
| 4 | 客户端 case | `easychat-front/src/main/wsClient.js` | 无 `case 24` / `case 25` → 对端实时收不到（**不崩不报错**） |
| 5 | 渲染分发 | `Chat.vue:136` 分发条件 | `1 \|\| 2 \|\| 5 \|\| 14 \|\| 20` 不含 24/25 → 即使历史拉到也不渲染；`ChatMessageVoice.vue` 是**从未被 import 的死组件** |

另有第六层（接线时才暴露）：本地文件服务器 `file.js` 的 `FILE_TYPE_CONTENT_TYPE`
缺 `fileType=3` → content-type 拼成 `undefinedwebm` → 浏览器无法解码、`<audio>` 静默无声。

## Requirements

### Requirement: 发送位置消息

用户可在单聊与群聊中发送位置消息，携带地址名称与经纬度。

#### Scenario: 发送成功

- **WHEN** 用户选择位置并填写地址（含经纬度）后发送
- **THEN** `POST /api/chat/sendMessage` 接受 `messageType=25`，返回 `code=0`
- **AND** 消息写入 `chat_message`，`message_content` 为地址文本，`extra_data` 为
  `{"location":"...","latitude":...,"longitude":...}` 的 JSON 字符串

#### Scenario: extraData 非法被拒

- **WHEN** `messageType=25` 但 `extraData` 不是合法 JSON、缺 `location` 字段、为空、或超 2000 字符
- **THEN** 返回 `code=1001`，不落库

#### Scenario: 对端实时收到

- **WHEN** 位置消息发送成功
- **THEN** 服务端推送 `messageType=25` 帧；`wsClient.js` 的 `case 25` 走真实消息路径
  （落本地 SQLite + 转发 `reciveMessage`），对端**无需刷新**即可看到

#### Scenario: 历史漫游

- **WHEN** 用户重新进入会话或换设备
- **THEN** `loadHistoryMessage` 能取回该位置消息并正常渲染（`ChatMessageLocation`，
  含经纬度详情与高德 URI 地图跳转）

#### Scenario: 可被搜索与撤回

- **WHEN** 用户用地址文本做全局搜索 / 在 2 分钟内撤回该位置消息
- **THEN** 均成功（搜索默认范围与撤回白名单均含 25）

---

### Requirement: 发送语音消息

用户可按住说话录制语音并发送，对端可点击播放并看到时长。

#### Scenario: 发送成功并落盘

- **WHEN** 用户录制结束发送语音
- **THEN** 前端**先**调 `sendMessage`（`messageType=24`，带 `file_name`/`file_size`/`duration`）
      拿到 `messageId`，**再**调 `POST /api/chat/uploadFile` 上传音频
- **AND** `chat_message` 产生 `message_type=24` 的行，`duration` 为秒数
- **AND** 音频落盘到 `file/{YYYYMM}/{messageId}.{ext}`

#### Scenario: 时长上限 60 秒

- **WHEN** `duration` 不在 `1..60`
- **THEN** 返回 `code=1001`，不落库
- **AND** 前端 `MAX_RECORDING_TIME` 为 `60000`（对标微信），到点自动停止
- **AND** `Constants.VOICE_MAX_DURATION_SECONDS` 是服务端同值守卫（`duration` 由客户端传，可伪造）

#### Scenario: 其他必填守卫

- **WHEN** `messageType=24` 但 `fileName` 为空、`duration` 为 null/小于 1、或 `fileType ≠ 3`
- **THEN** 返回 `code=1001`，不落库

#### Scenario: 音频后缀白名单

- **WHEN** 上传后缀为 `webm`/`m4a`/`mp3`/`wav`/`ogg`
- **THEN** 通过白名单（`allowedFileTypes` 默认值已含），按「音频」分类走 `maxFileSize`
- **AND** 白名单之外的音频后缀返回 `code=2604`
- **AND** 可执行文件黑名单 `DANGEROUS_SUFFIX_LIST` 仍生效
- **AND** **刻意不含 `.amr`**（微信同款）：兼容它需引入转码依赖，播放体验对用户不可感知

#### Scenario: 对端播放

- **WHEN** 语音消息发送成功
- **THEN** `wsClient.js` 的 `case 24` 走真实消息路径，对端无需刷新即可看到
- **AND** 点击后经主进程 express `/file` 端点取音频并播放，显示时长
- **AND** `FILE_TYPE_CONTENT_TYPE[3]` 为 `audio/*`（否则浏览器无法解码、静默无声）

#### Scenario: 历史漫游

- **WHEN** 用户重新进入会话
- **THEN** 历史语音可播放（音频文件仍在服务端磁盘上）

---

### Requirement: 语音未播放标记

语音消息在接收方播放前显示未播放红点，播放后消失（对标微信）。
状态是**每接收方独立**的。

#### Scenario: 为何用旁挂表

- **WHEN** 考察数据模型选型
- **THEN** 使用旁挂表 `chat_message_voice_read(message_id, user_id)`，
  **不**给 `chat_message` 加列
- **AND** 理由：「谁播了」是 per-receiver 状态，加列到共享消息表会让 A 播放污染 B 看到的行；
  且一个会话常有多条语音，加列无法表达「哪几条没播」（ADR-001）

#### Scenario: 未播放显示红点

- **WHEN** 接收方收到语音且尚未播放
- **THEN** 语音气泡显示未播放红点
- **AND** 状态取自服务端（跨重启/换设备一致），非本地标记

#### Scenario: 播放后标记已读

- **WHEN** 接收方点击播放
- **THEN** 前端**乐观清除红点**并调 `POST /api/chat/markVoiceRead`
- **AND** upsert `chat_message_voice_read` 的 `(message_id, user_id)` 行：`is_read=1`、`read_time=当前时间`
- **AND** 接口失败时**恢复红点**（不留下「界面显示已读、实际未记录」的不一致）

#### Scenario: 发送方本人不能标记

- **WHEN** 发送者对自己发出的语音调 `markVoiceRead`
- **THEN** 返回 `code=1001`
- **AND** 理由：红点是接收方的未播放提示，发送者标记会让红点永远消失

#### Scenario: 非接收方不能标记

- **WHEN** 无关第三方对他人语音调 `markVoiceRead`
- **THEN** 返回 `code=1001`
- **AND** 单聊须 `userId == message.contactId`；群聊须是该群成员（`user_contact.status=FRIEND`）

#### Scenario: 消息不存在或已删

- **WHEN** `markVoiceRead` 的 `messageId` 不存在或已删除
- **THEN** 返回 `code=2201`

#### Scenario: 非语音消息不能标记

- **WHEN** 对 `messageType ≠ 24` 的消息调 `markVoiceRead`
- **THEN** 返回 `code=1001`（防止拿红点表当通用标记表滥用）

#### Scenario: 批量查询已读状态

- **WHEN** 前端渲染消息列表时调 `POST /api/chat/loadVoiceRead`（`messageIdList` 逗号分隔）
- **THEN** 返回当前用户在这些消息中已播过的 `messageId` 列表
- **AND** 查询**强制带 `userId`**，不泄露他人播放状态
- **AND** 入参超过 200 个返回 `code=1001`；空列表直接返回空数组不查库

#### Scenario: 不新增 WS 帧

- **WHEN** 播放已读状态变化
- **THEN** 仅经 HTTP 写入，**不新增 WS 帧**
- **AND** 理由：红点是私有状态，对方不需要知道「我播了」（ADR-002）

---

## 门禁约束

| 门禁 | 作用 |
|------|------|
| `verify_ws_frame_parity.mjs` | 24/25 登记在 `MUST_HANDLE`（非 `KNOWN_GAP`）；落库白名单含 24/25 → 客户端必须有 case |
| `verify_file_type_content_type.mjs` | `FILE_TYPE_CONTENT_TYPE` 必须覆盖前端在用的 `fileType`；MIME 前缀须以 `/` 结尾；`fileType=3` 须为 `audio/*` |
| `mutation_ws_frame_parity.cjs` | 变异检验：删掉 `wsClient` 的 `case 25` 必须被抓 |

## 遗留（需本机 GUI 验证）

| 项 | 说明 |
|----|------|
| 真实 multipart `.webm` 上传 | 冒烟用 HTTP 字段构造，白名单放行分支未实跑 |
| 语音实际播放 | 需本机录一条发一条，确认 `<audio>` 出声 |
| `downloadFile` 对接收方可达性 | 冒烟未起 express 本地文件服务器；不通过则降级「仅发送方可播」，**不得为此放宽鉴权** |
| 位置地图跳转 | 需点击验证高德 URI 调起 |
| 聊天主路径回归 | 文本(2)/媒体(5)/撤回(14)/系统(3,8,9,11,12,26) 六类渲染未被带偏 |
| 红点跨重启一致性 | 需重启客户端验证 |
| 群聊分支越权防护单测 | `isVoiceReadReceiver` 的 GROUP 分支单测已补（`ChatMessageServiceImplTest` 3 例），**活体未验** |