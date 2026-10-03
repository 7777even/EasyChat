# Retro — 接通位置消息(LOCATION 25) 与语音消息(VOICE 24) + 语音未播放红点

- Change: `openspec/changes/2026-10-03-location-and-voice-message/`（L4）
- 日期: 2026-10-03

## 一、做得好

1. **先用 probe 取证再动手，而不是读代码推测**。写了 `probe_location_voice.py`，
   实测拿到「24/25 直接 `CODE_1001`」+「DB `message_type` 仅 `1 2`，0 条 24/25」。
   那个 0 条记录是**最有价值的一行证据**——它直接证明「这两个功能从未被真正使用过」，
   解释了为什么既有 spec 验收与常规冒烟全都漏掉了它。读代码只能得到「大概不通」，
   而这行 SQL 给出的是「从未通」。
2. **五层断链一次查全，没有「修完第一层就收工」**。probe 只暴露第 1 层（发送白名单），
   但顺着请求流追下去发现第 2/3/4/5 层全断。若只补 `ChatController` 的白名单，
   会得到「接口通了但消息不落库」「消息落了但对端收不到」「收到了但不渲染」的
   逐层暴露，每层都要重新发一轮版本。design §2.1 把五层画成一张流程图，是这一轮最值钱的产出。
3. **TDD 红阶段这次又是行为级红**。11 个 Error 全是 `UnsupportedOperationException` 桩。
   `ChatMessageVoiceRead` 的单测里两个「不变式」用例（写入 userId 必须是调用者本人 /
   `loadVoiceRead` 必须带 userId 过滤）在实现写歪时会立刻转红——
   这两条正是 per-receiver 语义（ADR-001）最容易被实现成「记到发送者名下」的地方。
4. **变异检验脚本成为交付物而非临时工具**。`mutation_ws_frame_parity.cjs` 已落
   `scripts/verify/` 并带前置守卫（工作区脏则 `exit 2`）。
   本轮它当场抓出**我自己新写的两条变异有漏判**（见问题 2），若无它就是「9/9 通过」的假结论。
5. **`FILE_TYPE_CONTENT_TYPE` 补 `"3": "audio/"` 是顺藤摸瓜的收获**。
   接线时发现 `fileType=3` 此前不在映射表里，content-type 会拼成 `"undefinedwebm"`，
   浏览器无法解码 → `<audio>` 播不出声音。
   这是**第五层之外的新一层**（本地文件服务器层），parity 门禁照不到（它只对账帧号）。
   若只按 design 的五层清单施工，这个会漏。

## 二、问题

1. **冒烟脚本自身踩了两个坑，都靠肉眼读输出才发现**：
   ① `post()` 返回 `(status, body)` 二元组，但我按 dict 用 → `AttributeError: 'tuple' object has no attribute 'get'`。
   ② 修成「统一返回 body」后，`send_msg()` 里的 `[1]` 又成了 `KeyError: 1`。
   两个坑叠加后的表现是「脚本静默无输出 + exit 0」——最坏的一种失败模式。
   根因是我复制了既有脚本的 `post()` 语义，却在 `send_msg()` 沿用了另一套取法，没统一。
2. **变异 6 被自己抓到两次漏判**（详见 QA §2.5）：
   ① 锚点未覆盖新增的 24/25 → 报 `[FAIL]`（这个是纪律生效：脚本把锚点失配判为失败而非跳过）
   ② 锚点只覆盖 `MEDIA_CHAT/VOICE/LOCATION`，残留 `CHAT/GROUP_CREATE/ADD_FRIEND`
      → 门禁仍能解析出 3 个落库帧 → exit=0 → `[MISSED]`
   第②次是真正的危险：它长得像「门禁没判别力」，实际是「我的变异没生效」。
   若只看汇总的 `[CAUGHT]` 计数，就会漏掉这个 MISSED。
3. **`ChatMessageVoice` 的 `fileType=3` content-type 缺失**（见问题「做得好」第 5 条反向）——
   它是**设计阶段没预见到的第六层断链**。design §2.1 的五层图只画到「渲染分发」，
   没往下追到「本地文件服务器如何按 fileType 决定 content-type」。
4. **群聊分支的越权防护只有单侧覆盖**。`isVoiceReadReceiver` 有 GROUP 分支
   （查群成员 status=FRIEND），但单测只覆盖了单聊的 USER 分支。
   冒烟也只测单聊 → **群聊下「非成员标记已读」这条路径无验证**。
5. **前端聊天主路径回归完全依赖人工**。本次改了 `Chat.vue` 分发条件、
   `ChatMessage.vue` 的 `v-else-if` 链与 `isNormalMessage`——这三个都在**所有消息**的必经路径上。
   build 通过只证明语法与导入正确，**不证明文本/媒体/撤回/系统消息没被带偏**。
   而项目没有 vitest（Retro 上一轮已列），这是结构性缺口不是本次疏忽。

## 三、原因

1. 复制既有冒烟脚本时只复制了「能跑」的部分，没统一其返回值约定；
   两套取法混用后异常类型（AttributeError / KeyError）恰好都发生在赋值后、
   `check()` 之前，于是「一行输出都没有」。
2. 变异脚本的锚点是**手写的源码片段**，而源码本身在本次被修改过（落库白名单加了 24/25）。
   手写锚点与真实源码之间没有任何校验机制——锚点失配是必然事件，不是意外。
   纪律（失配判 FAIL）救了我一次，但**漏判（锚点命中但语义不足）只能靠人眼**。
3. design 的五层图是**从请求流向回溯**得到的，而 content-type 问题位于
   「浏览器解码」这一段——它不在 HTTP 请求流里，而在**本地 express 文件服务器**里，
   属于另一条链路，回溯时自然看不到。
4. 我按「单聊是最常见场景」排了优先级，把群聊分支留到后面，然后就忘了。
   这与上一轮 retro 记录的「排期时把逻辑简单等同于不必测」是同一个模式的复发。
5. 前端无单测框架，是批次 5 早已登记的技术债。本次又踩了一次同样的坑。

## 四、改进方案

| 方案 | 落点 | 优先级 |
|------|------|--------|
| **补 `isVoiceReadReceiver` 群聊分支单测**：非群成员标记 → 拒绝；群成员 → 放行 | `ChatMessageServiceImplTest` | **立即** |
| **变异脚本锚点改为「从源码自动抽取」**：用正则按结构定位（如 `ArraysUtil.contains(new Integer[]{` 到 `})` 的整块），而非手写整段文本 | `mutation_ws_frame_parity.cjs` | **立即**（手写锚点必然随源码漂移） |
| **冒烟脚本统一返回值约定**：`post()` 只返回 body，需 status 另设 `post_full()`；并加一个「自身冒烟」（断言能拿到 code_of） | `scripts/smoke/*.py` 共用模板 | 高 |
| **设计阶段补「本地文件服务器」这一层**：`fileType` → content-type 映射表必须在任何文件类消息接线时同步检查 | design 模板 + `AGENTS.md` §6.4 | 高 |
| **`FILE_TYPE_CONTENT_TYPE` 补齐守卫**：门禁检查「前端所有 messageType 用到的 fileType 都在映射表里有」，否则 exit 1 | 新 `verify_file_type_content_type.mjs` | 高 |
| **补 multipart 上传冒烟**（`.webm` 放行 + `.exe` 硬拦截） | `smoke_location_voice.py` | 中 |
| 前端 vitest（覆盖 `Chat.vue` 分发条件与 `isNormalMessage` 这类主路径逻辑） | 批次 5 | 中（已第三次登记） |

## 五、给下一批的经验

- **DB 是功能的最后一任裁判**。`message_type` 只有 1/2 这条 SQL 记录，比任何代码阅读都更有说服力。
- **门禁报 ERROR 只是症状**。probe 报的 `CODE_1001` 是第 1 层；顺着请求流才发现后面还有 4 层。
  修到 probe 变绿 ≠ 功能可用。
- **「回溯请求流」会漏掉不在请求流里的层**。本地文件服务器的 content-type 映射、
  浏览器的解码能力，都不在 HTTP 链路里，但都能让功能静默失效。
- **变异脚本的锚点必须自动抽取**。手写源码片段在源码被改动后必然失配；
  更糟的是「锚点命中但覆盖不全」这种半失效，只能靠人眼发现。
- **半失效的变异伪装成「门禁无判别力」**。变异 6 的 `[MISSED]` 若不被逐条读，
  会得出「门禁这次退化了」的错误结论，而真相是「我的变异没写对」。
- **多分支的实现要按分支补测试，不能按「主路径够用」排优先级**。
  群聊分支漏测是本轮与上一轮重复出现的模式。