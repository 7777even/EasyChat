# Design — 群二维码与群邀请

- 关联 Proposal: 2026-09-30-group-qrcode-invite/proposal.md
- 创建日期: 2026-09-30

## 1. 架构设计

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `GroupQrCodeService` | 新增群二维码服务 | 生成/校验二维码 |
| `GroupInviteService` | 新增群邀请服务 | 生成/校验邀请链接 |
| `GroupController` | 新增接口 | 路由 + @Valid + 调 Service |
| `RedisComponet` | 新增二维码/邀请链接存储 | Redis 操作 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| `GroupDetail.vue` | 显示群二维码 | UI 展示 |
| `GroupInvite.vue` | 群邀请链接展示 | UI 展示 |

## 2. 接口设计

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/group/qrCode/generate` | POST | `{groupId}` | `Result<String>` | 群主/管理员 |
| `/api/group/qrCode/join` | POST | `{qrCode}` | `Result<Void>` | 登录用户 |
| `/api/group/invite/generate` | POST | `{groupId}` | `Result<String>` | 群主/管理员 |
| `/api/group/invite/join` | POST | `{inviteCode}` | `Result<Void>` | 登录用户 |

## 3. 数据模型

无表结构变更。二维码/邀请链接存 Redis：
- Key: `easychat:group:qrcode:{groupId}` / `easychat:group:invite:{groupId}`
- Value: 二维码/邀请链接 token
- TTL: 7 天

## 4. 安全设计

- 鉴权: 生成二维码/邀请链接需要群主/管理员权限
- 数据权限: 用户只能操作自己的群组
- 输入校验: @Valid 校验
- SQL 注入防护: 使用 `#{}`

## 5. ADR

### ADR-001: 二维码/邀请链接存储位置

- 状态: 已接受
- 上下文: 二维码/邀请链接需要快速读写
- 决策: 存 Redis，设置 7 天有效期
- 后果: 过期后需要重新生成

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 二维码/邀请链接被滥用 | 中 | 中 | 设置 7 天有效期 |
| 用户重复加群 | 低 | 低 | 幂等处理 |

## 7. 依赖与前提

- 需要 Redis 连接
- 需要群组服务
