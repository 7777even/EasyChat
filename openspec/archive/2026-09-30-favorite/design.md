# Design — 收藏功能

- 关联 Proposal: 2026-09-30-favorite/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `Favorite` | 新增实体 | 1:1 对应表结构 |
| `FavoriteMapper` | 新增 Mapper | 只写 SQL |
| `FavoriteService` | 新增 Service | 业务规则 |
| `FavoriteController` | 新增 Controller | 路由 + @Valid + 调 Service |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `ChatMessage.vue` | 消息右键菜单添加"收藏"选项 | UI 交互 |
| `Favorite.vue` | 收藏列表页面 | UI 展示 |

## 2. 接口设计

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/favorite/add` | POST | `{messageId}` | `Result<Void>` | 登录用户 |
| `/api/favorite/cancel` | POST | `{favoriteId}` | `Result<Void>` | 登录用户 |
| `/api/favorite/list` | GET | - | `Result<List<FavoriteVO>>` | 登录用户 |

## 3. 数据模型

| 表 | 变更 | 字段 | 类型 | 说明 |
|----|------|------|------|------|
| `favorite` | 新增 | `id` | BIGINT | 自增 ID |
| | | `user_id` | VARCHAR(12) | 用户 ID |
| | | `message_id` | BIGINT | 消息 ID |
| | | `content` | VARCHAR(500) | 收藏内容 |
| | | `file_path` | VARCHAR(255) | 文件路径 |
| | | `create_time` | BIGINT | 创建时间毫秒 |

> 变更需同步 `easychat.sql`。

## 4. 安全设计

- 鉴权: 收藏接口需要登录
- 数据权限: 用户只能操作自己的收藏
- 输入校验: @Valid 校验
- SQL 注入防护: 使用 `#{}`

## 5. ADR

### ADR-001: 收藏内容存储方式

- 状态: 已接受
- 上下文: 收藏需要保存消息内容
- 决策: 冗余存储消息内容，避免关联查询
- 后果: 消息删除后收藏内容仍可查看

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 收藏内容过时 | 中 | 低 | 显示收藏时间 |
| 收藏数据量过大 | 低 | 低 | 限制收藏数量 |

## 7. 依赖与前提

- 需要数据库连接
- 需要消息服务
