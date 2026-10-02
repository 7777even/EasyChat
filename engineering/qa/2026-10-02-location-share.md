# QA 报告 — 位置分享（前端补齐）

- 日期: 2026-10-02
- 关联 Change: openspec/changes/2026-09-30-location-share（前端阶段）
- 说明: 本变更 2026-09-30 曾归档，2026-10-01 复核发现前端为零而移回；本次补齐前端并重新归档。

## 范围

| 端 | 改动 |
|----|------|
| 前端渲染层 | `MessageSend.vue` 工具栏「位置」按钮 + 位置选择弹窗（地址输入 + 获取当前位置） |
| 前端组件 | 新增 `ChatMessageLocation.vue`（气泡 + 详情弹窗 + 在地图中打开） |
| 前端渲染层 | `ChatMessage.vue` 对 `messageType=25` 渲染位置气泡（自己/对方两个分支） |
| 后端 | 无改动（`MessageTypeEnum.LOCATION(25)` 与 `extra_data` 存储已存在） |

## 实现方式偏离（已人工确认）

原 `design.md` §7 写「需要地图 API（如高德/百度）」。实测全仓无地图能力，接入 SDK 属新增依赖（L3）。**2026-10-02 人工确认改为不引地图 SDK**：定位用 `navigator.geolocation`，地图跳转用 `shell.openExternal` + 高德 URI（无需 Key）。存储仍按 design §3 存 `extra_data`。

## 验收口径

1. 前端 0 新增 lint error、`npm run build` 通过。
2. 后端 `mvn test` 全绿。
3. 位置消息渲染接入 `ChatMessage.vue` 两个分支。

## 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `npx eslint src/renderer/src/views/chat/ChatMessageLocation.vue` | **0 error** |
| `npx eslint`（本次涉及文件） | 无新增 error（`MessageSend.vue` / `ChatMessage.vue` 的 error 均为既有，已用 HEAD 对照确认） |
| `npm run build` | built in 20.15s |
| `mvn -B -o test` | Tests run: **120**, Failures: 0 |
| `node scripts/check-openspec-hygiene.mjs` | 通过 |

## 未运行项

- **GUI 端到端**（点「位置」→ 弹窗 → 发送 → 对方看到气泡 → 点击详情）：沙箱无 GUI，未覆盖。
- **真实定位**：`navigator.geolocation` 需用户授权与真实设备，沙箱无法验证；代码含失败降级（仅提示，不阻塞发送）。
- **接口活体**：未在运行中的后端上实测 `messageType=25` 的发送与渲染。

## 结论

**通过**。前端 0 新增 lint error，构建通过，渲染链路完整。GUI 交互与真实定位待本机手动验证。

遗留：位置消息不落 `at_user_ids`、不触发 @ 提醒（设计如此）；`extra_data` 为 JSON 字符串，查询不便（design ADR-001 已记）。
