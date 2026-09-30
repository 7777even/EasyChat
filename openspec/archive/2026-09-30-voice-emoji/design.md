# Design — 语音消息与表情包

- 关联 Proposal: 2026-09-30-voice-emoji/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `MessageTypeEnum` | 新增 `VOICE(24)` | 帧类型枚举 |
| `EmojiController` | 新增表情包 CRUD 接口 | 路由 + @Valid + 调 Service |
| `EmojiService` | 表情包业务逻辑 | 事务边界 + 权限校验 |
| `EmojiMapper` | 表情包数据访问 | 只写 SQL |
| `Emoji` | 表情包实体 | 1:1 对应表结构 |
| `ChatMessageServiceImpl` | 支持语音消息存储 | 业务规则 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `MessageSend.vue` | 按住说话录音功能 | UI 交互 |
| `ChatMessageVoice.vue` | 语音消息播放组件 | UI 展示 |
| `EmojiPicker.vue` | 表情包选择器 | UI 展示 |
| 主进程 `file.js` | 录音文件处理 | 文件操作 |

## 2. 接口设计

### 表情包接口

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/emoji/list` | GET | - | `Result<List<EmojiVO>>` | 登录用户 |
| `/api/emoji/upload` | POST | `MultipartFile` | `Result<EmojiVO>` | 登录用户 |
| `/api/emoji/delete` | POST | `{emojiId}` | `Result<Void>` | 登录用户 |

### 错误码

复用现有错误码：
- `2603` 文件大小超限
- `2604` 文件类型不支持

## 3. 数据模型

| 表 | 变更 | 字段 | 类型 | 说明 |
|----|------|------|------|------|
| `emoji` | 新增 | `id` | BIGINT | 自增 ID |
| | | `user_id` | VARCHAR(12) | 用户 ID |
| | | `file_name` | VARCHAR(200) | 文件名 |
| | | `file_path` | VARCHAR(255) | 存储路径 |
| | | `file_size` | BIGINT | 文件大小 |
| | | `emoji_type` | TINYINT | 0=系统 1=自定义 |
| | | `create_time` | BIGINT | 创建时间毫秒 |

> 变更需同步 `easychat.sql`。

## 4. 安全设计

- 鉴权: 表情包接口需要登录
- 数据权限: 用户只能删除自己的表情包
- 输入校验: 语音文件最大 60 秒，表情包文件最大 5MB
- SQL 注入防护: 使用 `#{}`

## 5. ADR

### ADR-001: 语音消息存储格式

- 状态: 已接受
- 上下文: 语音消息需要存储为文件
- 决策: 存储为 MP3 格式，文件名为 `{messageId}.mp3`
- 后果: 需要限制录音时长

### ADR-002: 表情包存储位置

- 状态: 已接受
- 上下文: 表情包需要快速加载
- 决策: 存储在本地文件系统，数据库存储元信息
- 后果: 需要定期清理未使用的表情包

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 录音权限问题 | 中 | 高 | 提示用户授权麦克风权限 |
| 语音文件过大 | 中 | 中 | 限制录音时长 60 秒 |
| 表情包文件过大 | 低 | 中 | 限制文件大小 5MB |

## 7. 依赖与前提

- 需要麦克风权限
- 需要文件存储路径配置
