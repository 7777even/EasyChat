# Spec Delta — 新消息提醒：任务栏图标闪烁

- 关联 Tasks: 2026-09-24-desktop-notification/tasks.md
- 创建日期: 2026-09-24
- 需求修订: 2026-09-24 横幅方案撤销，改为任务栏图标闪烁（微信对齐）；本 delta 为修订后版本。

> 格式对齐 `openspec/specs/<capability>/spec.md`（Requirement / Scenario）。
> 与 proposal Capabilities C1/C2 一一对应。
> 目标 capability `desktop-notification` 为新建（`openspec/specs/` 现无此项）。

## ADDED Requirements

### Requirement: 新消息图标闪烁

主窗口未聚焦时，客户端收到新消息（文本、媒体、好友申请）应触发任务栏图标闪烁，直至窗口获得焦点；聚焦时不打扰；系统协议帧与误报场景不触发闪烁。

#### Scenario: 单聊文本消息失焦闪烁

- **WHEN** 主窗口未聚焦，用户收到单聊文本消息（messageType=2）
- **AND IF** 主窗口处于最小化状态
- **THEN** 任务栏图标进入**交替闪动动画**（600ms 周期亮/灭，微信式），持续至窗口获得焦点后停止
- **AND IF** 主窗口仅失焦未最小化
- **THEN** 任务栏按钮显示系统静态红底高亮（单次 flashFrame，人工验收确认可接受）

#### Scenario: 群聊与媒体消息失焦闪烁

- **WHEN** 主窗口未聚焦，群内其他成员发送文本或媒体消息（messageType=2/5）
- **THEN** 任务栏图标闪烁
- **AND** 消息内容仅进入会话列表与未读数，不外显于任何横幅/通知（无正文外显）

#### Scenario: 窗口聚焦时不打扰

- **WHEN** 主窗口处于聚焦状态时收到任何消息
- **THEN** 不触发闪烁（消息展示行为不变）

#### Scenario: 系统协议帧不闪（收敛既有全帧闪烁）

- **WHEN** 收到 ACK(-1)/SYNC/心跳/撤回(14)/群管理/文件上传完成等系统帧
- **THEN** 不触发闪烁
- **AND** 该帧的既有本地处理（入库、状态更新、渲染层推送）不受影响

#### Scenario: 抑制误报

- **WHEN** 收到以下任一情形：自身消息回声（`sendUserId` 为当前用户）、`sendTime` 距今超过 5 分钟的重连补推消息
- **THEN** 不触发闪烁，且消息本身正常入库与展示（抑制仅作用于闪烁）

#### Scenario: 好友申请失焦闪烁

- **WHEN** 主窗口未聚焦且收到好友申请帧（messageType=4）
- **THEN** 任务栏图标闪烁

#### Scenario: 点击闪烁按钮激活窗口（系统原生，无会话跳转）

- **WHEN** 用户点击处于闪烁状态的任务栏按钮
- **THEN** 窗口由系统原生激活并前置
- **AND** 不做会话级跳转（微信同款交互，需求修订明确移除横幅与跳转链路）

### Requirement: 提醒开关

用户可在设置页开启/关闭新消息闪烁提醒；配置持久化于本地用户设置，重启后保持；关闭后仅停用闪烁，不影响消息收发与未读数。

#### Scenario: 关闭提醒

- **WHEN** 用户在设置页关闭「新消息通知」
- **THEN** 后续新消息不触发任务栏闪烁
- **AND** 消息收发、未读数与会话列表刷新不受影响

#### Scenario: 开关持久化与缺省

- **WHEN** 用户切换开关并重启客户端
- **THEN** 开关状态保持
- **AND** 存量用户设置（无 `notifySwitch` 键）视为开启，无需迁移

#### Scenario: 开关写入不破坏既有配置键

- **WHEN** 保存提醒开关
- **THEN** 既有键（如 `localFileFolder`）原样保留
- **AND** 写入仅按当前登录用户自己的设置行定位（不可越权改他人）

#### Scenario: 托盘隐藏态的已知边界

- **WHEN** 主窗口经 `hide()` 隐藏至托盘（无任务栏按钮）
- **THEN** 闪烁不可见属系统限制（无按钮可闪），不视为缺陷
- **AND** 点击托盘图标恢复窗口后可立即看到未读

## MODIFIED Requirements

（无——`openspec/specs/` 现无覆盖任务栏闪烁的既有 Requirement；对既有「全帧闪烁」行为的收敛由上方新增 Requirement 的「系统协议帧不闪」Scenario 承载。）

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 新消息图标闪烁 | ADDED: 新消息图标闪烁 | 1.1, 1.2, 1.3, 1.4, 1.5, 4.2 |
| C2 提醒开关 | ADDED: 提醒开关 | 1.1（抑制规则 1）, 1.2（缓存初始化）, 2.1, 2.2, 4.3 |

## 原型描述（UI 变更，§4 前端 AGENTS 要求）

- **闪烁表现**: 无任何横幅/弹窗/声音——仅 Windows 任务栏按钮系统级闪烁，微信同款，两态（实现修订 2）：**最小化** → 600ms 交替闪烁循环（主动 stop/start 强制亮/灭动画）；**非最小化失焦** → 单次 `flashFrame(true)` 系统静态红底高亮；均至窗口获焦停止。用户点击闪烁中的任务栏按钮由系统原生激活窗口，无会话跳转。
- **设置页开关**: 账号设置页（`Setting.vue` 菜单 → 路由子页 `UserInfo.vue`，`showType==0` 详情态）「个性签名」行下方新增一行——左侧文案「新消息通知」+ 灰字说明「关闭后收到新消息将不再闪烁任务栏图标」，右侧 `el-switch`。
- **Chat.vue 无可见 UI 变化，且零代码改动**（原 `notificationClicked` 监听已随需求修订移除）。

## 回填记录

- 2026-09-24：2 Requirement / 11 Scenario 已回写 `openspec/specs/desktop-notification/spec.md`（新建 capability），同日归档至 `openspec/archive/2026-09-24-desktop-notification/`。验收遗留：运行时冒烟 8 项 + 回归 4 项 + 截图证据经人工当场豁免未执行，见 `engineering/qa/2026-09-24-desktop-notification.md` 未运行项。
