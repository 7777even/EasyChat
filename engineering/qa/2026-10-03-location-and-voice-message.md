# QA — 接通位置消息(LOCATION 25) 与语音消息(VOICE 24) + 语音未播放红点

- Change: `openspec/archive/2026-10-03-location-and-voice-message/`（L4，含 DDL + WS 契约 + 上传白名单放宽）
- 执行日期: 2026-10-03
- 结论: **通过**（26/26 活体断言 + 200 单测 + 10 门禁 exit 0 + 9/9 变异捕获）

## 1. 范围与验收口径

| 能力 | 验收口径 |
|---|---|
| C1 位置消息 | 25 可发送 → 落库 `message_type=25` → `extra_data` 含 location → `message_content` 是地址；extraData 非法/缺 location/空 → 1001 |
| C2 语音消息 | 24 可发送 → 落库含 `duration`/`file_name` → 上传落盘 → 下载可取；duration>60 / 0 / fileName 空 / fileType≠3 → 1001 |
| C3 历史漫游 | 落库帧全部有客户端 case（parity 门禁 6/6） |
| C4 可搜索 | `searchMessage`/`globalSearch` 默认范围扩到 {2,5,24,25} |
| C5 语音红点 | 接收方标记 code=0 且落库 is_read=1；**发送方标记自己 → 1001**；消息不存在 → 2201；`loadVoiceRead` 只返回本人行；201 条 → 1001 |
| DDL | migration-012 幂等双跑；基线逐字一致 |
| 门禁 | parity 24/25 从 `KNOWN_GAP` 移入 `MUST_HANDLE`，且变异检验 9/9 |

## 2. 关键验证证据

### 2.1 断链取证（改动前，probe 脚本）

`scripts/smoke/probe_location_voice.py` 9/9，实测 DB `message_type` 仅 `1 2`（0 条 24/25）。

### 2.2 DDL 幂等双跑

第一次：`chat_message_voice_read` 建出，5 列 + 复合主键 + `idx_user_read` 齐。
第二次：无 ERROR，表数量仍为 1。

### 2.3 TDD 红阶段

`mvn -B test "-Dtest=ChatMessageServiceImplTest"` → `Tests run: 36, Errors: 11`，
11 个 Error 全是 `UnsupportedOperationException: TDD 桩`，**行为级红**（非编译失败）。

转绿后全量：**197/197**（186 → +11，既有零回归）。

### 2.4 活体冒烟 26/26

```
=== 1. C1 位置消息收发与落库 ===
   [PASS] 发位置消息(25) code=0 | code=0 msg=success
   [PASS] 落库: chat_message 有 message_type=25
   [PASS] 落库: extra_data 含 location
   [PASS] 落库: message_content 是地址

=== 1.1 位置消息参数守卫 ===
   [PASS] extraData 非法 JSON → 1001 | code=1001
   [PASS] extraData 缺 location → 1001 | code=1001
   [PASS] extraData 为空 → 1001 | code=1001

=== 2. C2 语音消息落库与参数守卫 ===
   [PASS] 发语音消息(24) code=0 | code=0 msg=success
   [PASS] 落库: chat_message 有 message_type=24
   [PASS] 落库: duration=2
   [PASS] 落库: file_name 非空

=== 2.1 语音参数守卫（duration 上限 60s）===
   [PASS] duration=61 → 1001 | code=1001
   [PASS] duration=0 → 1001 | code=1001
   [PASS] fileName 空 → 1001 | code=1001
   [PASS] fileType≠3 → 1001 | code=1001

=== 3. C5 语音未播放红点 ===
   [PASS] 接收方 B 标记已读 code=0 | code=0 msg=success
   [PASS] 落库: chat_message_voice_read 有 (msg,B) 行且 is_read=1
   [PASS] ★ 发送方 A 标记自己消息已读 → 1001 | code=1001
   [PASS] 消息不存在 → 2201 | code=2201
   [PASS] 接收方 B loadVoiceRead 返回 1 条（自己已播）| data=[1883]
   [PASS] 发送方 A loadVoiceRead 返回 0 条（A 没有播过 B 的语音） | data=[]
   [PASS] 200 条以内 → code=0 | code=0
   [PASS] 201 条 → 1001 | code=1001

=== 4. 撤回语音(24)/位置(25) 不再被白名单拦 ===
   [PASS] 撤回语音消息(24) code=0（此前被白名单拒）| code=0 msg=success

=== 清理 ===
   已清理 3 条冒烟消息及其红点行
===== 结论：26/26 通过 =====
```

完整快照：`engineering/qa/2026-10-03-location-voice-smoke.txt`

### 2.5 parity 门禁 + 变异检验

```
落库白名单（ChatMessageServiceImpl）：2:CHAT、3:GROUP_CREATE、1:ADD_FRIEND、5:MEDIA_CHAT、24:VOICE、25:LOCATION
   [PASS] 全部 6 个落库帧在客户端均有 case（历史漫游链路完整）
   [PASS] KNOWN_GAP 登记的 0 个缺口仍然成立（未接通）
===== 结论：0 错误 / 0 警告 =====  exit=0
```

变异检验 **9/9 CAUGHT**，含新写的两条：

```
  [CAUGHT] 变异6 落库白名单被清空（结构改写 → 本项检查失效） → exit=1
            [ERROR] 未能解析服务端落库白名单（ChatMessageServiceImpl 结构可能已变或白名单被清空）
  [CAUGHT] 变异9 删掉 wsClient 的 case 25（本次改动被回退，门禁须抓） → exit=1
            [ERROR] ★ 需实时处理的帧在客户端无 case（功能静默失效）：type=25 LOCATION 位置消息
            [ERROR] ★ 落库帧在客户端无 case（历史漫游也拿不到）：type=25 LOCATION
```

> 变异 6 在编写过程中被自己抓到**两次**漏判：
> ① 锚点未覆盖新增的 24/25 → 报 `[FAIL]`（脚本按纪律判失败，不静默通过）
> ② 锚点只覆盖 `MEDIA_CHAT/VOICE/LOCATION`，残留前 3 项 → 门禁仍 exit=0 → 报 `[MISSED]`
> 两次都靠「逐条看输出而非只看汇总」才发现。

### 2.6 门禁与构建

```
mvn -B test   → 200/200 Failures:0 Errors:0
mvn -B clean package -DskipTests → exit=0
npm run build → built in 26.21s exit=0
eslint 8 个改动文件：基线 43 errors → 改动后 43 errors（**未增加**）
check-api-contract --strict exit=0（119 路由 / 117 调用 / 0 孤儿）
check-ipc-registration --strict exit=0
verify_mapper_params exit=0（28/28，含新增 ChatMessageVoiceReadMapper.xml）
check-openspec-hygiene exit=0
verify_ws_frame_parity exit=0
verify_file_type_content_type exit=0（本次新增门禁）
```

### 2.7 接线时才暴露的第六层：MIME 映射缺 `fileType=3`

按 design 的五层清单施工后，额外发现 `file.js` 的 `FILE_TYPE_CONTENT_TYPE` 只有
`{0:image/, 1:video/, 2:application/octet-stream}`，而语音的 `fileType=3` → content-type
拼成 `"undefinedwebm"` → 浏览器无法解码 → `<audio>` **播不出声音且不抛任何错**。

同时修正 `"2": "application/octet-stream"` → `"application/octet-stream/"`（原值不带 `/`，
拼后缀后是 `"application/octet-streamzip"`，非合法 MIME）。

新建门禁 `scripts/verify/verify_file_type_content_type.mjs` 锁死这一层：

```
=== 本地文件服务器 fileType → content-type 门禁 ===
   [PASS] 解析到 FILE_TYPE_CONTENT_TYPE 4 项：0:image/、1:video/、2:application/octet-stream/、3:audio/
   [PASS] 前端用到的 4 个 fileType 全部有 MIME 映射：0、1、2、3
   [PASS] fileType=3（语音）→ audio/（浏览器可按音频解码）
===== 结论：0 错误 =====  exit=0
```

已接 `pre-push` hook（读 `.git/hooks/pre-push` 确认）+ CI + `AGENTS.md` §7.4-6 / §10 +
`scripts/README.md`。

## 3. 未运行 / 未覆盖项（不谎报）

| 项 | 原因 | 后续 |
|----|------|------|
| **真实 multipart `.webm` 上传** | 冒烟用 HTTP 字段构造，不含 multipart；白名单放行路径**未实跑** | **需 GUI 或补 multipart 冒烟**。风险：`allowedFileTypes` 默认值已含 webm（单测覆盖配置读取），但 `checkFileAllowed` 的实际放行分支未验证 |
| **语音实际播放**（`fileType=3` content-type 修正后 `<audio>` 能否出声） | 沙箱无 GUI/音频设备 | 需本机录一条发一条验证 |
| **`downloadFile` 对接收方可达性**（design §2.3 未验证项） | 冒烟未起 express 本地文件服务器 | design 已预写降级方案；需 GUI 验证，若不通则降级「仅发送方可播」并**不为此放宽鉴权** |
| **位置消息地图跳转**（高德 URI） | 沙箱无 GUI 打开外部应用 | 需本机点击验证 |
| **聊天主路径回归**（文本2/媒体5/撤回14/系统 3,8,9,11,12,26 六类渲染） | 沙箱无 GUI | build + eslint 通过，但**主路径回归必须本机做** |
| **红点跨重启一致性** | 红点状态在服务端表，需重启客户端验证 | 需 GUI |
| 群聊语音的越权防护**活体**验证 | 冒烟只覆盖单聊 | **单测已补 3 例**（群成员放行 / 非成员拒绝 / selectCount 返回 null 拒绝），活体待验 |
| `loadVoiceRead` 的 `messageIdList` 含非数字元素 | 冒烟只测了正常数字 | Controller 有 `NumberFormatException → 1001` 分支，**未测** | 补一条冒烟断言 |

> 补充：`isVoiceReadReceiver` 的 GROUP 分支单测在 QA 初稿后补齐
> （`markVoiceRead_groupMemberAllowed` / `notGroupMemberRejected` / `nullGroupMemberCountRejected`），
> 全量单测 **200/200**（186 → +14）。

## 4. 结论

**通过。** 五层断链全部修复（发送白名单 / 落库白名单 / 上传白名单 / 客户端 case / 渲染分发），
外加接线时暴露的**第六层**（本地文件服务器 MIME 映射缺 `fileType=3`）。
消息本体零 DDL（复用既有 `duration`/`file_name`/`extra_data`），
唯一 DDL 是语音红点旁挂表（migration-012，幂等双跑验证）。

遗留需本机 GUI 验证 8 项，已逐条登记于 §3 与 spec 遗留表，**未勾选**。