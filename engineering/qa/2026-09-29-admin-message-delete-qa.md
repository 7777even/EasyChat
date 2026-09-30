# QA — 管理端消息删除位（delete_flag + 20 帧实时同步）

- 日期: 2026-09-29
- 效率等级: L4
- 范围: `easychat-migration-009-message-delete.sql`（chat_message 列）、`easychat.sql` 基线；后端 `MessageTypeEnum`/`ChannelContextUtils`/`ChatMessageService(+Impl)`/`AdminReportServiceImpl`/`ChatMessageMapper.xml`/`ChatMessage` PO；前端 `wsClient.js`/`Chat.vue`/`ChatMessage.vue`/`ReportList.vue`/`ChatSessionUserModel.js`；冒烟脚本 `scripts/smoke/smoke_admin_msg_delete.py`。关键路径：举报处置删除链、用户侧三查询过滤、20 帧在线/离线推送（规则①②）、三路 2201 守卫、幂等。

## 验收口径

- migration-009：`chat_message.delete_flag` 存在且 `bigint NOT NULL DEFAULT 0`，存量全量存活（`WHERE delete_flag=0` 返回全量）。
- 处置删除：`dealReport(reportType=3, handleAction=1)` → `delete_flag` 置位（处置时刻 ms），`handle_note` 双写报告行与审计日志；同消息重复处置幂等（不改写、不重推、code=0）。
- 用户侧不可见：`loadHistoryMessage` / `searchMessage` / `globalSearch` 均不过滤返回被删消息（SQL 字面 `AND delete_flag=0`，无 HTTP 开关）。
- 管理端证据保留：`getReportDetail`（PK 读）与 `chat_message` PK 行仍返回原文。
- 2201 守卫：已删消息的 `locateMessage` / `recallMessage` / `downloadFile` 返回 HTTP 400 + body `code=2201`。
- Redis 离线队列（规则①②）：发送方离线时其队列出现 20 帧且 `contactId`=会话对方；群离线成员（含发送者成员）队列 20 帧且 `contactId`=groupId；既有单聊普通消息离线补推 JSON（`contactId`=发送者）不受两规则影响。
- 服务端预览占位：被删消息为会话最新时 `chat_session.last_message`=「该消息已被管理员删除」，非最新不动。
- `dealReport` 其余分支回归：0 仅记录（delete_flag 不动）、2 封禁发布者、朋友圈类处置均 code=0。
- 契约与卫生门禁：`check-api-contract` 0 孤儿 0 漂移；`check-openspec-hygiene` 0 错误。
- 构建：后端 `mvn compile` 0 error；前端 `npm run build` 0 error。

## 实际执行命令与结果

- `mvn compile` → 0 error（阶段二收尾）。
- `npm run build`（easychat-front）→ BUILD_EXIT=0。
- `python scripts/smoke/smoke_admin_msg_delete.py engineering/qa/2026-09-29-admin-msg-delete-smoke.txt` → **43/43 PASS**（P0 迁移 4、P1 单聊链 17、P2 幂等 4、P3 群聊 7、P4 分支回归 6、后端可达 1、登录 0 计入流程），`SMOKE PASS`，退出码 0。
- `node scripts/check-api-contract.mjs` → 0 潜在孤儿 / 0 潜在漂移（98 后端路由 vs 96 前端 + 3 主进程调用），退出码 0。
- `node scripts/check-openspec-hygiene.mjs` → 0 错误 / 0 警告，退出码 0。
- 冒烟 finally 自动回滚全部 fixture：`delete_flag` 还原、双会话 `last_message` 还原、`message_report`/`report_audit_log`/`moment_report` fixture 行删除、朋友圈 `status` 与 karina `status` 还原、4 个离线队列清空（脚本输出第 51–60 行逐项留痕）。

## 未运行项

- **双实例 WS 冒烟（移交用户本机 GUI 执行）**：单聊接收方在线收 20 帧墓碑+预览占位、离线重连补推、发送方其他设备补推且会话联系人非「自己」、群离线成员重连补推不出脏会话、群在线成员墓碑、右键菜单隐藏、通知不闪。服务端队列侧语义已由冒烟 P1/P3 断言，剩余为客户端渲染侧。
- **页面截图（前端 AGENTS §4 要求，随双实例项一并补附）**：`ChatMessage.vue` 墓碑渲染、`ReportList.vue` 处置警示新文案——沙箱无 GUI，无法截图；截图待用户本机验证时补入本目录。
- 双实例通话/媒体验证、虚拟滚动/桌面通知补测（①②遗留项，与本变更无关，同样移交）。

## 证据附件

- `2026-09-29-admin-msg-delete-smoke.txt`：冒烟全量终端输出（43 项逐条断言 + 帧 JSON 片段 + cleanup 回滚留痕）。
- 关键帧语义摘录（见附件 L18/L41/L42）：发送方 20 帧 `contactId=U29953535216`（会话对方，规则①）、群成员 20 帧 `contactId=G08427252986`（规则②）、普通消息 20 帧前基线 `contactId=U04259455805`（转换不受影响）。

## 结论

- **达成**：验收口径全部通过（冒烟 43/43 + 双门禁绿 + 双端构建 0 error）。遗留：双实例 WS 渲染侧验证与 UI 截图移交用户本机执行（清单见归档交付说明）。
- ~~既有缺陷（不在本变更范围，不修复）：`ChatMessageServiceImpl.downloadFile` 中 `UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)` 为 Integer 比 enum 恒 false，群文件成员/归属校验实际未生效——建议另立变更修复。~~ **→ 2026-09-30 已修复，见下节。**

---

# 渲染层实测（agent-browser）— 2026-09-30

## 方法与覆盖边界

- **方法**：独立 Vite dev server（`http://localhost:5000`，临时配置 `easychat-front/vite.ec-probe.mjs`，测后已删）+ 经 `transformIndexHtml` 在模块脚本前内联注入 `ec-shim.js`（临时文件，测后已删）。垫片仿真 `window.ipcRenderer` 全部 36 通道与主进程 `wsClient.js` 的 WS 帧分发（含 20 帧墓碑化、条件预览占位、ACK、SYNC_SESSION、心跳、离线补推）。
- **覆盖边界（如实声明）**：
  1. 垫片是**仿真**，不是真实主进程。主进程 `wsClient.js` 的 case 20（本地 SQLite 墓碑化）**未由本测试覆盖**，其正确性由代码复核 + 冒烟 P1/P3 的 Redis 队列侧断言共同支撑。
  2. 垫片不仿真 SQLite：`loadChatMessage` 首屏改走服务端 `POST /chat/loadHistoryMessage`（pageSize 50）+ 内存墓碑补回，`pageTotal` 固定 1 使 `noData=true`，后续上翻交回 `ChatMessage.vue` 自带的云端漫游路径。
  3. **页面截图未能取得**：`browser.screenshot` 需要桌面浏览器窗口前置，实测期间窗口不可见，连续 3 次返回 `Screenshot needs a visible tab`。本节结论全部基于 DOM 断言（`innerHTML` / `outerHTML` / 事件派发），不依赖截图。
  4. 右键菜单用合成 `MouseEvent('contextmenu')` 派发到 `ChatMessage` 根元素（`.message-content-my`）触发，非真实鼠标右键；已用正常消息做对照（菜单出现）证明派发路径有效。

## 实测结果

| # | 场景 | 操作 | 断言 | 结果 |
|---|------|------|------|------|
| R1 | 垫片就位 | 加载 `index.html` | `window.ipcRenderer` / `window.__ec.installed` 存在；`#app` 渲染出登录面板；`__ec.errors` 为空 | ✅ |
| R2 | Vue 调度健康 | 登录页 `init()` 执行 | 无 `isFlushing` 卡死；`loadLocalUser`/`setLocalStore`×4 正常入 `sends` 日志 | ✅ |
| R3 | UI 登录 | karina `karina7710@test.com` 验证码登录 | 跳转 `#/chat`；`userId=U04259455805`；`wsOpen=true`；`initDone=true`；2 会话 | ✅ |
| R4 | 会话列表渲染 | — | DOM 2 条 `.chat-session-item`，与 `__ec.sessions` 一致 | ✅ |
| R5 | 消息历史加载 | 点击单聊会话 | 服务端历史 27 条落内存并渲染；`errors` 空 | ✅ |
| R6 | 发送消息 | 输入 `SMKDEL-UI-001 墓碑渲染验证` 回车 | 消息 1855 入列；收到 `-1` ACK 与 `-6` SYNC_SESSION；会话预览刷新 | ✅ |
| R7 | **在线墓碑（单聊）** | `del_msg.py 1855` | 20 帧到达 `contactId=U29953535216`、`lastMessage=该消息已被管理员删除`；本地行 `messageType=20`；**会话预览=「该消息已被管理员删除」** | ✅ 预览 / ❌ 消息区 |
| R8 | **消息区墓碑渲染** | 检查 `#message1855` | `outerHTML` = `<div class="message-item" id="message1855"><!--v-if--><!--v-if--><!--v-if--></div>` —— 三个分支全 false，**渲染为空 div** | ❌ **缺陷坐实** |
| R9 | 右键菜单（对照） | 对正常消息 1854 派发 contextmenu | `.mx-context-menu` 出现，含「复制/引用回复/转发」 | ✅ |
| R10 | 右键菜单（墓碑） | 对 `#message1855` 派发 contextmenu | 无 `.mx-context-menu`（因 `ChatMessage` 未挂载，`onContextMenu` 无宿主） | ✅（但属 R8 缺陷的连带结果） |
| R11 | **离线补推** | 发 1856 → `dropWs()` → `del_msg.py 1856` → `reconnectWs()` | 重连后 20 帧补推到达（`f20` 1→2）；1856 墓碑化；预览=「该消息已被管理员删除」 | ✅ |
| R12 | **群聊墓碑** | admin 登录 → 群 `G08427252986` 发 1857 → `del_msg.py 1857` | 20 帧 `contactId=G08427252986`（规则②不转换）；行墓碑化；预览占位；**消息区仍为空 div** | ✅ 预览 / ❌ 消息区 |
| R13 | 无脏会话 | 群墓碑后检查会话列表 | 会话数 3 不变，`contactId` 集合不变 | ✅ |
| R14 | 群聊右键 | 对 `#message1857` 派发 contextmenu | 无菜单 | ✅ |
| R15 | 管理端走查 | `#/admin/reportList` | 列表渲染，3 条测试举报均「已处理」 | ✅ |
| R16 | 管理端走查 | `#/admin/callLog` | 页面渲染正常（本环境无通话记录，显示「暂无数据」） | ✅ |

## Review 结论：**需修改**

- **修改点（唯一）**：`easychat-front/src/renderer/src/views/chat/Chat.vue:113`
  ```diff
  - v-if="data.messageType == 1 || data.messageType == 2 || data.messageType == 5 || data.messageType == 14"
  + v-if="data.messageType == 1 || data.messageType == 2 || data.messageType == 5 || data.messageType == 14 || data.messageType == 20"
  ```
- **依据**：`ChatMessage.vue:19-20/74-75` 已实现 20 墓碑 UI（「该消息已被管理员删除」），但 `Chat.vue` 模板的门控条件不含 20，`ChatMessageSys` 分支（3/1/9/8/11/12）也不含 20，导致 `ChatMessage` 永不挂载。规格 `openspec/specs/content-moderation/spec.md` L149 要求渲染层显示墓碑文案。
- **实测证据**：R8/R12 的 `#message1855` / `#message1857` 均为 `<!--v-if--><!--v-if--><!--v-if-->` 空 div；同页会话预览已正确显示「该消息已被管理员删除」，证明数据链路（20 帧 → 本地行墓碑化 → `extendData.lastMessage`）全部正确，**仅模板门控漏改**。
- **提交溯源**：`9075e02`（渲染层墓碑）只新增了 20 帧处理分支与 `ChatMessage.vue` 的墓碑 UI，未同步修改 `Chat.vue:113` 门控条件。
- 其余验收项（预览占位、右键隐藏、离线补推、群聊不脏会话、管理端页面）全部通过。

## 证据附件

- 本文件 R1–R16 的 DOM 断言记录（`outerHTML` 原文、帧日志、`__ec.errors` 全程为空）。
- 测试驱动脚本（临时，测后已删）：`del_msg.py`（admin 登录 → `/report/chat` → `/admin/report/dealReport handleAction=1`）。
- 脚手架（临时，测后已删）：`easychat-front/vite.ec-probe.mjs`、`ec-shim.js`。**`index.html` 未被修改**，仓库无残留。

---

# 修复与复验 — 2026-09-30

## 修复内容

`easychat-front/src/renderer/src/views/chat/Chat.vue:113`（唯一改动，一行）：

```diff
- v-if="data.messageType == 1 || data.messageType == 2 || data.messageType == 5 || data.messageType == 14"
+ v-if="data.messageType == 1 || data.messageType == 2 || data.messageType == 5 || data.messageType == 14 || data.messageType == 20"
```

## 复验结果

| # | 场景 | 断言 | 修复前 | 修复后 |
|---|------|------|--------|--------|
| R8' | 单聊消息区墓碑 | `#message1859` 渲染墓碑文案 | `<!--v-if--><!--v-if--><!--v-if-->` 空 div | ✅ `.content-panel.recalled-message > .content.recalled-content > .recall-text` =「该消息已被管理员删除」 |
| R12' | 群聊消息区墓碑 | `#message1858` 渲染墓碑文案 | 空 div | ✅ 同上结构，文案正确 |
| R10' | 单聊墓碑右键 | 无 `.mx-context-menu` | 无菜单（组件未挂载） | ✅ 无菜单（`onContextMenu` 守卫 `isRecalled \|\| !isNormalMessage` 生效） |
| R14' | 群聊墓碑右键 | 无菜单 | 无菜单 | ✅ 无菜单 |
| R7'/R13' | 会话预览占位 | 「该消息已被管理员删除」 | ✅ | ✅ |

- 复验中 `window.__ec.errors` 全程为空；单聊/群聊两条路径均确认。
- 虚拟列表说明：删除帧到达后虚拟列表需一次 scroll 事件才重渲染窗口（`scrollTop` 已在底部但窗口未刷新），派发 `scroll` 后 `#message1859` 正常出现——此为虚拟列表既有行为，与本修复无关。

## 复验后结论

- **规格符合性**：`openspec/specs/content-moderation/spec.md` L149「渲染层显示墓碑文案」现已达成。
- **Review 结论**：原「需修改」项已闭环，无新增问题。

---

# 遗留缺陷修复：downloadFile 越权 — 2026-09-30

## 缺陷

`easychat-java/src/main/java/com/easychat/service/impl/ChatMessageServiceImpl.java`

```java
418:  UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
419:  if (UserContactTypeEnum.USER.getType().equals(contactTypeEnum) && ...) {   // Integer.equals(enum) 恒 false
422:  if (UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)) {          // 同上
```

`getType()` 返回 `Integer`，`contactTypeEnum` 是 `UserContactTypeEnum` —— `Integer.equals(enum)` **恒为 false**，两个授权分支均为死代码。

**后果**：任何登录用户只要拿到 `messageId` 即可下载**任意单聊文件**（419 归属校验失效）与**任意群文件**（422 群成员校验失效）—— 水平越权（IDOR）。

**排查范围**：全仓 8 处 `UserContactTypeEnum` 枚举变量逐一核对，`Integer.equals(enum)` 写法**仅此 2 处**；其余 6 处（`ChatMessageServiceImpl:593/733/738`、`UserContactServiceImpl:203/225/230/244/253/261`）实参为 `Integer`，`Integer.equals(Integer)` 正确。同文件 `214` 行已有正确写法可参照。

## 修复

```diff
- if (UserContactTypeEnum.USER.getType().equals(contactTypeEnum) && !userInfoDto.getUserId().equals(message.getContactId())) {
+ if (UserContactTypeEnum.USER == contactTypeEnum && !userInfoDto.getUserId().equals(message.getContactId())) {
- if (UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)) {
+ if (UserContactTypeEnum.GROUP == contactTypeEnum) {
```

改用枚举同一性比较（`==`），与同文件 `214` 行既有写法一致。`contactTypeEnum` 为 `null` 时 `==` 安全（不抛 NPE）。

## 验证

- `mvn compile` → 0 error。
- 运行时验证（4/4 PASS，证据 `engineering/qa/2026-09-30-download-authz-fix.txt`）：

| 场景 | fixture | 期望 | 实际 |
|------|---------|------|------|
| 单聊 未授权（admin 非 contactId） | 1781（contact=bbqy） | 1001 | 1001 ✅ |
| 单聊 授权（admin 是 contactId） | 1796（contact=admin） | ≠1001 | 2104（授权通过，文件不在盘）✅ |
| 群聊 未授权（karina 非成员） | 1852（群 G08427252986） | 1001 | 1001 ✅ |
| 群聊 授权（admin 是成员） | 1852 | ≠1001 | 1002（授权通过）✅ |

**判定逻辑**：修复前两个分支恒不进入 → 任何人都能走到文件查找；修复后未授权被 1001 拦截、授权方不再返回 1001。1001 与「非 1001」即可区分修复是否生效。

## 附带发现（不在本次范围，不修）

- ~~群聊授权对照返回 `1002`（系统错误）：`downloadFile` 对**文本消息**（`fileName` 为 null）会在 `StringTools.getFileSuffix(null)` 抛 NPE。属既有缺陷，与本次越权修复无关，建议另立变更处理。~~ **→ 2026-09-30 已修复，见下节。**
- 单聊授权语义：`contactId` 为会话**对方**，故发送者本人下载自己发出的文件也会被 1001 拦截。此为既有设计意图（代码注释与逻辑一致），本次仅修复比较运算符，未改变该语义。

---

# 遗留缺陷修复：downloadFile 文本消息 NPE — 2026-09-30

## 缺陷

`ChatMessageServiceImpl.downloadFile` 对**非文件消息**（文本/图片等 `fileName` 为 null）会在
`StringTools.getFileSuffix(null)` 抛 NPE（`null.substring(...)`），冒泡到全局异常处理器 →
**HTTP 500 + code=1002（系统错误）**。

触发路径：`POST /api/chat/downloadFile`，`fileId` 为任意文本消息 ID，`partType` 留空。

## 修复

```diff
  String fileName = message.getFileName();
+ // 文本等非文件消息 fileName 为空 → 直接按「文件不存在」返回，避免 getFileSuffix(null) NPE 冒泡成 1002
+ if (StringTools.isEmpty(fileName)) {
+     logger.info("消息无文件 messageId={}", messageId);
+     throw new BusinessException(ResponseCodeEnum.CODE_2104);
+ }
  String fileExtName = StringTools.getFileSuffix(fileName);
```

复用同方法既有的 `CODE_2104`（文件不存在）语义，不新增错误码。

## 验证

- `mvn compile` → 0 error。
- 运行时验证（1/1 PASS，证据 `engineering/qa/2026-09-30-download-no-npe-fix.txt`）：

| 场景 | fixture | 修复前 | 修复后 |
|------|---------|--------|--------|
| 文本消息下载（授权通过） | 1852（message_type=2, file_name=NULL） | HTTP 500 + code=1002 | HTTP 400 + code=2104 ✅ |

修复前 NPE 堆栈（旧后端日志留痕）：
```
java.lang.NullPointerException: Cannot invoke "String.lastIndexOf(String)" because "fileName" is null
    at com.easychat.utils.StringTools.getFileSuffix(StringTools.java:88)
    at com.easychat.service.impl.ChatMessageServiceImpl.downloadFile(ChatMessageServiceImpl.java:439)
```
