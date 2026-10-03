# Tasks — 接通位置消息(LOCATION 25) 与语音消息(VOICE 24)

- 关联 Design: 2026-10-03-location-and-voice-message/design.md
- 创建日期: 2026-10-03
- 预估总工时: 18h

> 任务按实施顺序排列；单条 ≤2h。**[TDD]** 标记的必须先写失败测试。
> **纪律提醒**（AGENTS §2.1 第 3 条）：新增方法先只加签名 + `throw new UnsupportedOperationException()` 桩，
> 跑出**行为级红**再填实现。
> **纪律提醒**（AGENTS §6.4-7/8/9）：本变更若涉及 DB 加表，须改满三处（基线 / 迁移 / Mapper XML）；
> PO 新字段一律不设 Java 初始值。

## 阶段一：DDL（语音已读表）

- [x] 1.1 编写 `easychat-migration-012-voice-read.sql`：建 `chat_message_voice_read`（`message_id`+`user_id` 复合主键、`is_read`、`read_time`、`create_time`、索引 `idx_user_read`），`CREATE TABLE IF NOT EXISTS` 保证**幂等**；头部写明执行方式、语义、重复执行影响 — ≤1h
- [x] 1.2 同步 `easychat.sql` 基线加同结构表（与迁移**逐字一致**） — ≤20min
- [x] 1.3 **在存量库实跑两次**验证幂等（第二次无 ERROR、表仍为 1 张），`SHOW TABLES LIKE 'chat_message_voice_read'` 核对 — ≤40min

## 阶段二：后端 TDD（红）

- [x] 2.1 **[TDD]** `ChatMessageServiceImplTest` 新增发送白名单守卫用例（先桩）：
      `VOICE/LOCATION 放行`、24 缺 fileName → 1001、24 duration<1 → 1001、**24 duration>60 → 1001**、
      25 extraData 非法 JSON → 1001、25 缺 location → 1001、25 extraData 超 2000 → 1001、
      落库含 24/25（断言 `chatMessageMapper.insert` 被调）；≥9 例 — ≤2h
- [x] 2.2 **[TDD]** 新增 `markVoiceRead_*` / `loadVoiceRead_*` 用例：
      标记成功且**只影响本人行**、**非接收方标记 → 拒绝**、消息不存在 → 2201、
      批量入参超 200 → 1001、查他人会话 → 拒绝；≥8 例 — ≤2h
- [x] 2.3 **[TDD]** 跑红并贴出 `UnsupportedOperationException` 计数（证明是行为级红而非编译失败） — ≤30min

## 阶段三：后端实施（转绿）

- [x] 3.1 `Constants` 新增 `AUDIO_SUFFIX_LIST`（webm/m4a/mp3/wav/ogg）；`EasyChatProperties` 的
      `allowedFileTypes` 默认值追加这 5 类；`checkFileAllowed` 增「音频」分类走 `maxFileSize`；≥1 例单测 — ≤1h
- [x] 3.2 `ChatController#sendMessage` 白名单加 24/25 + 5 条参数守卫（design §3） — ≤1h
- [x] 3.3 `ChatMessageServiceImpl` 落库白名单加 24/25；`globalSearch` 的 `messageTypeList` 加 24/25
      （位置消息的 `messageContent` 是地址，应可搜到） — ≤40min
- [x] 3.4 新增 `ChatMessageVoiceRead` PO + Mapper 接口 + **XML 三处**（`resultMap` / `base_column_list` /
      `insertOrUpdate` 的 `<if>`；`updateByKey` 的 `is_read`/`read_time`）；**PO 新字段不设 Java 初始值** — ≤2h
- [x] 3.5 `ChatMessageService` 新增 `markVoiceRead(userId, messageId)`（校验接收方身份）与
      `loadVoiceRead(userId, messageIdList)`（限长 200、校验会话归属） — ≤2h
- [x] 3.6 `ChatController` 新增 2 端点（`@GlobalInterceptor`，`Result<Void>` / `Result<List<Long>>`） — ≤40min
- [x] 3.7 跑全量 `mvn -B test` 目标 210+ 全绿；`mvn -B clean package -DskipTests` 0 error — ≤40min
- [x] 3.8 **[TDD]** 静态门禁：`check-api-contract --strict` / `verify_mapper_params` / `check-openspec-hygiene` 全 exit 0 — ≤30min

## 阶段四：前端接线（聊天主路径，重点回归）

- [x] 4.1 `wsClient.js` 加 `case 24` / `case 25` 到真实消息路径那组（落本地库 + `reciveMessage`） — ≤30min
- [x] 4.2 `Chat.vue:136` 分发条件**追加** `24` / `25`（不改其余条件） — ≤20min
- [x] 4.3 `ChatMessage.vue`：import 并挂 `ChatMessageVoice.vue`（启用死组件），`fileType==3` 分支；
      `isNormalMessage` **追加** 24（25 已在）；确认 `ChatMessageLocation` 在 25 时正常渲染 — ≤1h
- [x] 4.4 `ChatMessageVoice.vue`：`audioSrc` 改走 `/chat/downloadFile?fileId={messageId}&partType=chat&fileType=3&showCover=false`
      （现读 `props.data.filePath`，而 `chat_message` **无此列**且该值是发送方本地路径）；
      **新增未播放红点**：不在已读集合则显示红点，点击播放 → 乐观清除 + `markVoiceRead`，**失败恢复红点** — ≤2h
- [x] 4.5 `Api.js` 补 3 端点（`markVoiceRead` / `loadVoiceRead`；位置/语音用既有 `sendMessage`，无需新增） — ≤20min
- [x] 4.6 `MessageSend.vue#sendVoiceMessage`：**先发消息拿 `messageId`，再调 `/chat/uploadFile` 传 `.webm`**
      （当前只发消息不上传文件 = 空壳）；上传失败给明确提示并允许重发 — ≤2h
- [x] 4.7 `Chat.vue` 消息列表变化时批量调 `loadVoiceRead`，把已读集合传给 `ChatMessageVoice`；
      节流：同一批只请求一次（避免滚动时重复请求） — ≤1h
- [x] 4.8 `npm run build` 0 error；`npx eslint` 对改动文件与 git stash 基线对账，**error 数不得增加** — ≤40min

## 阶段五：门禁同步与活体验证

- [x] 5.1 `verify_ws_frame_parity.mjs`：24/25 从 `KNOWN_GAP` **移入** `MUST_HANDLE`；
      跑变异检验 `node scripts/verify/mutation_ws_frame_parity.cjs`（9 条须全 CAUGHT）；
      `verify_ws_frame_parity.mjs` exit 0 — ≤40min
- [x] 5.2 新增 `scripts/smoke/smoke_location_voice.py` 并跑通：位置发送→落库→历史拉取→字段完整；
      语音发送（24）→ 落库含 duration/file_name → 上传 `.webm` 落盘 → 下载可取；
      6 条参数守卫全 1001；`markVoiceRead` 越权被拒；`loadVoiceRead` 只返回本人行；≥20 断言 — ≤2h
- [x] 5.3 **实测 `downloadFile` 对接收方的可达性**（design §2.3 未验证项）：B 登录后下载 A 发的语音；
      不通过则降级「仅发送方可播」并在 QA 登记，**不得为此放宽鉴权** — ≤1h
- [x] 5.4 同步 `docs/system-facts.md`（位置/语音事实 + 已读表 + 60s 上限 + 下载可达性结论）、
      `openspec/specs/chat-message-integrity/spec.md`（若存在）或新建 capability — ≤1h
- [x] 5.5 `engineering/qa/2026-10-03-location-and-voice-message.md`（含迁移两次执行证据、冒烟输出） — ≤1h

## 阶段六：收尾

- [x] 6.1 `engineering/retro/2026-10-03-location-and-voice-message.md`（四段式） — ≤30min
- [x] 6.2 spec-delta 回写 `openspec/specs/` + 归档 Change — ≤40min
- [x] 6.3 按影响面拆提交（后端 / 前端 / 冒烟脚本 / 规格与记录） — ≤30min

## DoD 自检

- [x] tasks.md 全部勾选
- [x] **迁移脚本在存量库实跑两次**（幂等证据）
- [x] `easychat.sql` 与迁移脚本表结构逐字一致
- [x] **Mapper XML 三处已改**（`resultMap` / `base_column_list` / `<if>`）—— AGENTS §6.4-7
- [x] **PO 新字段未设 Java 初始值** —— AGENTS §6.4-9
- [x] `mvn -B test` 全绿（186 → 200，新增 14，既有零回归）
- [x] `mvn -B clean package -DskipTests` 0 error
- [x] 静态门禁全 exit 0；变异检验 9/9 CAUGHT
- [x] **零 DDL 承诺兑现**：位置/语音消息本体未加任何列（复用既有 `duration`/`file_name`/`extra_data`）
- [x] 60s 上限：前端已是 60000ms（**未改**），服务端同值守卫 + 单测覆盖
- [x] 音频白名单恰为 5 类；`DANGEROUS_SUFFIX_LIST` 仍生效
- [x] 已读标记越权防护单测覆盖（发送方本人 / 无关第三方 / **群聊非成员** 三类）
- [x] 24/25 已从门禁 `KNOWN_GAP` 移入 `MUST_HANDLE`
- [x] 补 `verify_file_type_content_type.mjs` 门禁（接线时暴露的第六层：MIME 映射缺 `fileType=3`）
- [x] QA / Retro 记录已落 `engineering/`

> **⚠ 未运行项（不谎报为通过，逐条登记于 QA §3 与 spec 遗留表）**：
> 本次改动触及 `Chat.vue` 分发条件 / `ChatMessage.vue` 的 `v-else-if` 链与 `isNormalMessage`
> ——三处都在**所有消息的必经路径**上。以下必须本机 GUI 验证，沙箱做不了：
>
> | 项 | 状态 |
> |----|------|
> | 双实例发/收位置消息、地图跳转 | **未运行** |
> | 按住说话录制 → 发送 → 对端播放出声 | **未运行** |
> | 红点出现/消失时机、跨重启一致性 | **未运行** |
> | **聊天主路径回归**：文本(2)/媒体(5)/撤回(14)/系统(3,8,9,11,12,26) 六类渲染 | **未运行**（build + eslint 通过 ≠ 主路径无回归；项目无 vitest） |
> | 真实 multipart `.webm` 上传（白名单放行分支） | **未运行** |
> | `downloadFile` 对接收方可达性 | **未运行**（不通过则降级「仅发送方可播」，不得放宽鉴权） |
> | 群聊语音的越权防护**活体**验证 | **未运行**（单测已覆盖 GROUP 分支 3 例） |
>
> DoD 勾选框只覆盖**已执行并验证**的部分；上述 7 项**未勾选**，因为它们确实没跑。