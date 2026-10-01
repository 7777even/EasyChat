# Design — 状态

- 关联 Proposal: 2026-09-30-user-status/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

```
用户设置状态 → POST /userStatus/set → 落库（user_status 表）→ 好友在详情页看到状态 → 24h 后自动过期
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| UserStatus.java | 新增 PO 实体 | 1:1 对应 user_status 表 |
| UserStatusMapper.java | 新增 Mapper 接口 | 数据访问 |
| UserStatusMapper.xml | 新增 MyBatis XML | SQL 映射 |
| UserStatusService.java | 新增 Service 接口 | 业务逻辑 |
| UserStatusServiceImpl.java | 新增 Service 实现 | 业务规则 + 事务边界 |
| UserStatusController.java | 新增 Controller | 路由 + @Valid + 调 Service |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| UserDetail.vue | 显示好友状态 + 设置自己的状态 | 用户交互 + 渲染 |
| Api.js | 新增状态相关 API | API 路径常量 |

## 2. 接口设计

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/userStatus/set` | POST | `{ content, imageUrl }` | `Result<Void>` | 登录用户 |
| `/api/userStatus/get` | GET | `{ userId }` | `Result<UserStatusVO>` | 登录用户 |
| `/api/userStatus/clear` | POST | - | `Result<Void>` | 登录用户 |

### 错误码

复用现有错误码：
- `1001` 参数非法（content 为空或超过 500 字符）
- `2101` 用户不存在

## 3. 数据模型

| 表 | 变更 | 字段 | 类型 | 说明 |
|----|------|------|------|------|
| `user_status` | 新增 | `user_id` | VARCHAR(64) | 用户 ID |
| | | `content` | VARCHAR(500) | 状态文字内容 |
| | | `image_url` | VARCHAR(500) | 状态图片 URL |
| | | `create_time` | BIGINT | 创建时间戳 |
| | | `expire_time` | BIGINT | 过期时间戳 |

> 变更需同步 `easychat.sql`。

## 4. 安全设计

- 鉴权: `@GlobalInterceptor` 登录校验
- 数据权限: 用户只能设置/清除自己的状态
- 输入校验: `@Valid` 规则，content 非空且不超过 500 字符
- SQL 注入防护: MyBatis `#{}` 参数绑定

## 5. ADR

### ADR-001: 状态存储方式

- 状态: 已接受
- 上下文: 状态如何存储？
- 决策: 新增 user_status 表，每个用户一条记录
- 后果: 实现简单，查询高效

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 状态图片存储压力 | 低 | 中 | 限制 1 张图片 |
| 状态过期清理 | 中 | 低 | 查询时过滤过期状态 |

## 7. 依赖与前提

- 无前置依赖
