# Retro — 位置分享（前端补齐）

- 关联 Change: openspec/changes/2026-09-30-location-share
- 关联 QA: engineering/qa/2026-10-02-location-share.md

## 做得好

1. **发现 design 的隐含依赖并主动求证，而不是硬着头皮接 SDK**。原 design 写「需要地图 API」，但全仓无地图能力、接入 SDK 属 L3 依赖变更。选择先问再做，避免了一个「为了发个位置引入整个地图 SDK」的过度设计。
2. **降级策略清晰**：定位失败不阻塞发送（仅提示），保证核心链路（发位置）始终可用；无经纬度时「在地图中打开」按钮禁用，不给用户死路。
3. **存储复用 `extra_data`**，与 design §3 一致，零 DDL 变更。

## 问题

1. **spec 与实现脱节**：原 spec 是 2 句占位（「用户选择位置并发送位置消息。」），却假设了地图选点弹窗这一未确认的交互。
2. **「后端已定义消息类型」被当成「功能可用」**：`MessageTypeEnum.LOCATION(25)` 存在，但前端无人产生、无人渲染，等于该类型不存在。
3. **图标复用 `icon-top`（图钉）**：iconfont 无位置图标，`icon-location` 在 moment 模块是**未定义图标**（既有显示缺陷），本次只能借用图钉，语义偏差。

## 原因

- design 写了「需要地图 API」却没标注这是**依赖变更**，导致实现阶段才发现要引 SDK。
- 归档判据仍是「tasks 全勾 + QA 存在」。

## 改进方案

| 优先级 | 方案 | 落点 |
|--------|------|------|
| 高 | design 里凡写「需要 X SDK / X 服务」，必须在 Impact 标注依赖变更等级（L3/L4） | `templates/_openspec-design_template.md` |
| 中 | 补 `icon-location` 等缺失图标（需 iconfont 源文件重新生成），或统一用 SVG 图标体系 | `assets/icon/` |
| 中 | 位置消息可考虑接入系统地图 URL Scheme（`geo:` / `maps:`）作为「在地图中打开」的补充 | 后续 |

## 遗留 / 后续

- GUI 交互与真实定位待本机手动验证。
- `icon-location` 等未定义图标的修复需 iconfont 源，归入 UI 债清理。
