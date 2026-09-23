# 证据 — 删除已读回执功能（2026-09-23）

> 本文件为 `2026-09-23-remove-read-receipt.md` QA 的证据附件，逐条对应验收口径。所有输出为实际终端快照（摘录）。

## E1 静态验证（T1–T4 DoD）

```
# 后端编译
mvn compile -q   →  EXIT=0

# 后端残留（T1/T2 DoD，两次实测均 0）
git grep -nE 'messageReadService|handleClientAck|WS_CLIENT_ACK|WS_ACK_NOTIFY|sendAckNotify|batchGetAck|markRead|DELIVERED' -- easychat-java/src/main/java
→ 0 hits OK
git grep -in 'message_read' -- easychat-java/src/main easychat.sql
→ 0 hits OK

# 运行库表（T2 DoD）
DROP TABLE IF EXISTS message_read_record;
SHOW TABLES LIKE 'message_read_record';   → 空结果
# 归档前复查（information_schema）
residue_read_table = 0

# 前端主进程（T3 DoD）
esbuild transformSync: wsClient.js / ipc.js / index.js / Api.js → ESBUILD OK ×4
git grep -nE 'sendClientAck|ackNotify|messageType: -3' -- easychat-front/src/main → 0 hits OK
git grep -n 'onRegisterPendingAck' -- easychat-front/src/main → 4 hits (保留项 ≥3 ✓)

# 渲染层（T4 DoD）
@vue/compiler-sfc parse+compileScript+compileTemplate: Chat.vue / ChatMessage.vue → SFC OK ×2, 0 errors
git grep -nE 'ackType|ackNotify|sendClientAck|markRead|batchGetAck|已送达|readBy' -- easychat-front/src/renderer → 0 hits OK
```

## E2 死端点 404（T6-③）

```
POST http://127.0.0.1:5050/api/chat/markRead?contactId=...&contactType=0&messageIds=1
→ {"code":1003,"message":"资源不存在","data":null} HTTP_CODE=404
GET  http://127.0.0.1:5050/api/chat/batchGetAck?messageIds=1,2,3
→ {"code":1003,"message":"资源不存在","data":null} HTTP_CODE=404
对照组 GET /api/account/checkCode → HTTP_CODE=200
```

## E3 消息收发与撤回回归（T6-②）

```
# U042 发文本 → code=0, messageId=1831
send(tok1)={"status":"success","code":0,...,"data":{"messageId":1831,...}}

# A'（新代码，CDP 注入 U2995）收帧 + 本地落库
收到服务器消息 {..."messageId":1831,"messageType":2,...}
执行的sql:insert or replace into chat_message(...,1831,2,1790155571447,U04259455805,...,U29953535216) 执行记录数:1

# 撤回回归（发送方多端撤回副本）
recall={"status":"success","code":0,...,"data":{"messageId":1831,...,"messageType":14,"lastMessage":"该消息已撤回"}}
执行的sql:update chat_session_user set last_message=?,...params:该消息已撤回,1790155627472,...
执行的sql:update chat_message set message_type = ?,message_content = ?,status = ? where message_id = ? and user_id = ?,params:14,该消息已撤回,1,1831,U29953535216 执行记录数:1

# 图片消息（先建 type5 消息行、后 multipart 上传 messageId+file+cover）
sendType5={..."data":{"messageId":1832,"messageType":5,...,"status":0}} → code=0
upload={"status":"success","code":0,...}                              → code=0
A' 收帧+落库: insert or replace(...file_name,file_size,file_type...,1832,5,...) 执行记录数:1
媒体状态帧(6)回执: 收到服务器消息 {..."messageId":1832,"messageType":6,"status":1}
执行的sql:update chat_message set status = ? where message_id = ? and user_id = ?,params:1,1832,U29953535216 执行记录数:1
```

## E4 -3/-5 踪迹归零（T6-①，全程累计扫描）

```
扫描模式:
  前端 A' 日志: ackNotify|sendClientAck|messageType":-3|messageType":-5|case -5|case -3
  后端日志:     ackNotify|handleClientAck|batchAck|message_read|WS_CLIENT_ACK
分阶段扫描（连接后 / 收1831 / 撤回1831 / 收1832+上传后）均 0 命中
最终: total trace hits=0
WS 双端在线: A'(50873→27156, 新代码) + B(52884→34596, 旧代码自动重连) 2 条连接
```

## E5 冒烟数据回滚基线（T6 DoD2）

```
MySQL（session 380571aaeed37621c53433dcbd238254）:
  行数 14 → 删除 1831/1832 → sess_cnt=12（=基线）
  预览回填: last_receive_time=1790149769028, CHAR_LENGTH(last_message)=1, HEX(RIGHT(last_message,1))='32'（即字面 '2'，=基线）
  message_read_record: residue=0

本地库 C:\Users\7even\.easychatdev\local.db:
  U2995 会话行=11（总 16 = 11 会话 + 5 Urobot 历史），锚点 ('2',1790149769028,no_read=0) =基线
  U042 会话行=8（总 9 = 8 会话 + 1 欢迎语），锚点 ('该消息已撤回',1790149784227,no_read=0) =基线
  冒烟行 residue: select ... where message_id in (1831,1832) → []
  user_setting.contact_no_read 已全量归 0

redis easychat:msg:seq:380571aa... = 23（冒烟全程未漂移）
上传残留: c:/easychat/file/202609/1832.png、1832.png_cover.png 已删除，目录为空
```
