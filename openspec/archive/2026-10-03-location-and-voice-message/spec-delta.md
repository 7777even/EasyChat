# Spec Delta — 接通位置消息(LOCATION 25) 与语音消息(VOICE 24)

- 关联 Tasks: 2026-10-03-location-and-voice-message/tasks.md
- 创建日期: 2026-10-03

## ADDED Requirements

### Requirement: 发送位置消息

用户可在单聊与群聊中发送位置消息，携带地址名称与经纬度。

#### Scenario: 发送成功

- **WHEN** 用户在单聊/群聊选择位置并填写地址（含经纬度）后发送
- **THEN** `POST /api/chat/sendMessage` 接受 `messageType=25`，返回 `code=0`
- **AND** 消息写入 `chat_message`，`message_content` 为地址文本，`extra_data` 为
  `{"location":"...","latitude":...,"longitude":...}` 的 JSON 字符串

#### Scenario: 落库

- **WHEN** `messageType=25` 通过服务端发送白名单与落库白名单
- **THEN** `chat_message` 产生 `message_type=25` 的行（`ChatMessageServiceImpl` 落库白名单含 25）

#### Scenario: extraData 非法被拒

- **WHEN** `messageType=25` 但 `extraData` 不是合法 JSON，或缺 `location` 字段
- **THEN** 返回 `code=1001`，不落库

#### Scenario: extraData 超长被拒

- **WHEN** `messageType=25` 且 `extraData` 超过 `extra_data` 列宽（2000 字符）
- **THEN** 返回 `code=1001`，不落库

#### Scenario: 对端实时收到

- **WHEN** 位置消息发送成功
- **THEN** 服务端推送 `messageType=25` 帧；`wsClient.js` 的 `case 25` 走真实消息路径
  （落本地 SQLite + 转发 `reciveMessage`），对端**无需刷新**即可看到

#### Scenario: 历史漫游

- **WHEN** 用户重新进入会话或换设备
- **THEN** `loadHistoryMessage` 能取回该位置消息并正常渲染（`ChatMessageLocation`）

#### Scenario: 渲染与地图跳转

- **WHEN** 前端渲染 `messageType=25`
- **THEN** 显示地址与经纬度；点击弹出详情；「在地图中打开」经高德 URI 调起系统默认地图

#### Scenario: 可被搜索

- **WHEN** 用户用地址文本做全局搜索
- **THEN** 该位置消息被命中（`globalSearch` 的 `messageTypeList` 含 25）

#### Scenario: 右键菜单

- **WHEN** 用户右键位置消息
- **THEN** 提供复制 / 引用回复 / 转发（`ChatMessage.vue` 的 `isNormalMessage` 已含 25）

---

### Requirement: 发送语音消息

用户可按住说话录制语音并发送，对端可点击播放并看到时长。

#### Scenario: 发送成功并落库

- **WHEN** 用户录制结束发送语音
- **THEN** 前端**先**调用 `sendMessage`（`messageType=24`，带 `file_name`/`file_size`/`duration`）
      拿到 `messageId`，**再**调用 `POST /api/chat/uploadFile` 上传音频文件
- **AND** `chat_message` 产生 `message_type=24` 的行，`duration` 为秒数
- **AND** 音频落盘到 `file/{YYYYMM}/{messageId}.{ext}`

#### Scenario: 时长上限 60 秒

- **WHEN** `messageType=24` 且 `duration` 不在 `1..60`
- **THEN** 返回 `code=1001`，不落库
- **AND** 前端 `MAX_RECORDING_TIME` 保持 `60000`（对标微信），到点自动停止

#### Scenario: 缺少文件名或时长被拒

- **WHEN** `messageType=24` 但 `fileName` 为空，或 `duration` 为 null / <1
- **THEN** 返回 `code=1001`，不落库

#### Scenario: 音频后缀白名单

- **WHEN** 上传后缀为 `webm`/`m4a`/`mp3`/`wav`/`ogg` 之一
- **THEN** 通过白名单校验，按「音频」分类走 `maxFileSize` 上限
- **AND** 白名单之外的音频后缀返回 `code=2604`（类型不支持）
- **AND** 可执行文件黑名单 `DANGEROUS_SUFFIX_LIST` 仍然生效

#### Scenario: 对端实时收到并播放

- **WHEN** 语音消息发送成功
- **THEN** `wsClient.js` 的 `case 24` 走真实消息路径，对端无需刷新即可看到
- **AND** 点击后经 `/api/chat/downloadFile` 取得音频并播放，显示时长

#### Scenario: 历史漫游

- **WHEN** 用户重新进入会话
- **THEN** 历史语音可播放（音频文件仍在服务端磁盘上）

---

### Requirement: 语音未播放标记

语音消息在接收方播放前显示未播放红点，播放后消失（对标微信）。状态是**每接收方独立**的。

#### Scenario: 未播放显示红点

- **WHEN** 接收方收到语音且尚未播放
- **THEN** 语音气泡显示未播放红点

#### Scenario: 播放后标记已读

- **WHEN** 接收方点击播放语音
- **THEN** 前端**乐观清除红点**并调用 `POST /api/chat/markVoiceRead`
- **AND** 写入 `chat_message_voice_read` 的 `(message_id, user_id)` 行，`is_read=1`、`read_time=当前时间`
- **AND** 接口失败时**恢复红点**（不留下「界面显示已读、实际未记录」的不一致）

#### Scenario: 状态是每接收方独立的

- **WHEN** A 播放了 B 发来的语音
- **THEN** 只影响 A 自己的 `chat_message_voice_read` 行，**不影响** B 及其他接收方

#### Scenario: 不可替他人标记

- **WHEN** 非该消息接收方调用 `markVoiceRead`
- **THEN** 返回错误（`1001`），不写入任何行

#### Scenario: 批量查询已读状态

- **WHEN** 前端渲染消息列表时调用 `POST /api/chat/loadVoiceRead`（`messageIdList` 逗号分隔）
- **THEN** 返回当前用户在这些消息中的已读 `messageId` 列表
- **AND** `messageIdList` 超过 200 个时返回 `1001`

#### Scenario: 不可查询他人会话

- **WHEN** 调用 `loadVoiceRead` 查询自己不在的会话的消息
- **THEN** 返回错误，不泄露他人状态

#### Scenario: 消息不存在

- **WHEN** `markVoiceRead` 的 `messageId` 不存在或已删除
- **THEN** 返回 `2201`（消息不存在）

#### Scenario: 不新增 WS 帧

- **WHEN** 播放已读状态变化
- **THEN** 仅经 HTTP 写入，**不新增 WS 帧**
- **AND** 对方不需要知道「我播了」（红点是私有状态）

---

## MODIFIED Requirements

### Requirement: 服务端发送消息白名单

`POST /api/chat/sendMessage` 接受的 `messageType` 由 `{CHAT(2), MEDIA_CHAT(5)}` **放宽**为
`{CHAT(2), MEDIA_CHAT(5), VOICE(24), LOCATION(25)}`。契约**放宽**而非新增端点，出参 `Result<MessageSendDto>` 不变。

#### Scenario: 既有类型不受影响

- **WHEN** `messageType` 为 2 或 5
- **THEN** 行为与改动前**完全一致**

#### Scenario: 非法类型仍被拒

- **WHEN** `messageType` 不在上述四者之内（如 3/4/13/14）
- **THEN** 返回 `code=1001`

#### Scenario: 放行不等于无校验

- **WHEN** `messageType` 为 24 或 25
- **THEN** **仍执行**各自的必填与格式守卫（见上两条 Requirement），不因放行而跳过校验

---

### Requirement: 上传文件类型白名单

`easychat.file-upload.allowed-file-types` 默认值由
`jpg,jpeg,png,gif,bmp,webp,pdf,doc,docx,xls,xlsx,ppt,pptx,zip,rar,txt,mp4,mp3`
**追加** `webm,m4a,wav,ogg`（`mp3` 原本已在，故音频共 5 类）。
新增 `Constants.AUDIO_SUFFIX_LIST` 供 `checkFileAllowed` 分类大小上限。

#### Scenario: 可执行文件仍被硬拦截

- **WHEN** 上传 `.exe`/`.bat`/`.js`/`.sh` 等
- **THEN** 返回 `2604`，音频白名单的放宽**不影响**此拦截

#### Scenario: 大小上限按音频分类

- **WHEN** 上传音频文件
- **THEN** 按 `sysSetting.maxFileSize`（默认 15MB）判定超限，返回 `2603`

#### Scenario: 既有类型不受影响

- **WHEN** 上传图片/视频/文档
- **THEN** 分类与上限逻辑**完全不变**

---

### Requirement: WebSocket 帧意图登记

帧 24/25 的客户端 case 补齐后，在 `verify_ws_frame_parity.mjs` 中**从 `KNOWN_GAP` 移入 `MUST_HANDLE`**。

#### Scenario: 门禁锁定接线

- **WHEN** 有人删掉 `wsClient.js` 的 `case 24` 或 `case 25`
- **THEN** `verify_ws_frame_parity.mjs` 报 ERROR 并 exit 1（功能静默失效被阻断）

#### Scenario: 缺口登记表保持准确

- **WHEN** 24/25 已接通但未从 `KNOWN_GAP` 删除
- **THEN** 门禁报「`KNOWN_GAP` 已过期」并 exit 1，防止登记表腐烂

---

## REMOVED Requirements

无。既有 `chat_message.delete_flag` 语义、`/chat/downloadFile` 鉴权守卫、
`sendMessage` 的敏感词过滤与 ACK 可靠性协议全部保留不变。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 位置消息收发 | ADDED: 发送位置消息 | 2.1, 3.2, 3.3, 4.1, 4.2, 4.3, 5.2 |
| C2 语音录制发送播放 | ADDED: 发送语音消息 | 2.1, 3.1, 3.2, 3.3, 4.1, 4.4, 4.6, 5.2 |
| — 语音已读红点（用户追加） | ADDED: 语音未播放标记 | 1.1–1.3, 2.2, 3.4–3.6, 4.4, 4.5, 4.7, 5.2 |
| C3 历史漫游 | ADDED: 位置/语音的「历史漫游」场景 | 3.3, 4.1, 5.2 |
| C4 位置可搜索 | ADDED: 发送位置消息 · 可被搜索 | 3.3, 5.2 |
| — 契约放宽 | MODIFIED: 服务端发送消息白名单 | 2.1, 3.2 |
| — 契约放宽 | MODIFIED: 上传文件类型白名单 | 3.1 |
| — 门禁同步 | MODIFIED: WS 帧意图登记 | 5.1 |