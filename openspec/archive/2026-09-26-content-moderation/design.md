# 设计：内容治理（举报 + 敏感词过滤）

## 1. 架构总览

```
[前端]
  ChatMessage.vue 右键"举报" ─┐
  Moment.vue 动态/评论"举报" ─┤→ ReportDialog.vue → POST /moment/report | /chat/report
                              │
[发送链路注入敏感词过滤]
  ChatController.sendMessage ─→ ChatMessageServiceImpl.saveMessage
      └─ sensitiveWordService.filter(content)  // level3 抛错 / level1,2 替换
  MomentController.publish ─→ MomentServiceImpl.publish
  MomentController.comment ─→ MomentServiceImpl.addComment
      └─ sensitiveWordService.filter(content)

[后端]
  SensitiveWordService（@PostConstruct 加载词库到内存）
  ReportService（幂等落库 moment_report / message_report）
  ReportController（/moment/report、/chat/report）
```

## 2. 关键决策（ADR）

### ADR-1 敏感词匹配用内存全量遍历 `String.contains`
- 理由：词库规模小（百级以内），`O(N*M)` 遍历足够；避免引入 AC 自动机/第三方库的复杂度与依赖。
- 替换：`content.replace(word, "***")`（全部替换）。
- 加载时机：`@PostConstruct` 启动时从库 `SELECT * FROM sensitive_word WHERE status=1`；预留 `reload()` 供未来管理端调用（本变更不暴露端点）。

### ADR-2 过滤异常码 `CODE_2701`（新增 2700-2799 内容治理域）
- 文件域 2600-2699 已被 2601-2604 占用（`CODE_2603`=文件大小超限），故敏感词另辟 `2700-2799` 内容治理域。
- 前端收到 2601/2701 等错误由 `Request` 统一拦截提示，发送方看到"内容包含敏感词，禁止发送"。

### ADR-3 举报幂等
- 同一 `report_user_id` 对同 `(moment_id|comment_id|message_id)` 且 `status=0` 已存在记录时，直接返回成功，不重复落库（防止刷举报、重复数据）。

### ADR-4 复用既有错误码校验举报对象
- 举报动态/评论：moment 不存在用 `CODE_2501`；评论不存在用 `CODE_2501`（评论归属朋友圈，复用动态不存在码亦可，故统一 2501）。
- 举报消息：message 不存在用 `CODE_2201`。

### ADR-5 仅落库、不做处理端
- 本变更不实现举报处理/审核界面（属管理端能力，用户未点名）。`moment_report.handle_user_id/create_time/handle_time` 字段保留供后续。

## 3. 数据影响

- 运行期写：`sensitive_word`（仅启动时读）、`moment_report`、`message_report`（用户举报时写）。
- 无 DDL、无存量数据迁移。
- 种子 SQL `easychat-migration-005-sensitive-word-seed.sql` 提供示例词（level 2/3 若干），**不自动执行**，由用户按需初始化词库（空词库=无拦截，符合"先有词才生效"）。

## 4. 风险与对策

| 风险 | 对策 |
|------|------|
| 敏感词误杀正常消息（level3 过宽） | 词库由管理员维护；v1 仅示例种子，量级可控；level1/2 为替换不阻断 |
| 过滤在 saveMessage 内抛错导致发送失败 | 前端 `Request` 统一提示；非阻塞其它消息 |
| 重复举报刷库 | ADR-3 幂等 |
| 空词库时 filter 无操作 | 内存列表空直接返回原内容，链路无副作用 |

## 5. 依赖

- 无新第三方依赖。
- 复用：`@GlobalInterceptor`、`BusinessException`、`ResponseCodeEnum`、`StringTools`、`@PostConstruct`。
