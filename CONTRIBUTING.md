# EasyChat 工程协作约定

> 本文件是 `AGENTS.md` 中提交约定的速查版，面向人和 AI 共同遵守。

## 提交格式

提交信息格式：`type(scope): 描述`

### type 枚举

| type | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | 修复 Bug |
| `refactor` | 重构（不改变外部行为） |
| `perf` | 性能优化 |
| `style` | 代码格式（不影响功能） |
| `docs` | 文档更新 |
| `test` | 测试相关 |
| `chore` | 构建/工具/杂项 |
| `ci` | CI/CD 配置 |
| `revert` | 回滚提交 |

### scope 枚举

| scope | 说明 |
|-------|------|
| `auth` | 认证/鉴权 |
| `user` | 用户信息 |
| `chat` | 聊天/消息 |
| `group` | 群组 |
| `contact` | 好友/联系人 |
| `moment` | 朋友圈/动态 |
| `file` | 文件上传 |
| `ws` | WebSocket |
| `admin` | 管理后台 |
| `common` | 公共组件/工具 |
| `config` | 配置 |
| `docs` | 文档 |
| `chore` | 杂项 |

### 规则

1. 提交信息**只写一行标题**，禁止正文/body
2. 跨域改动**按影响面拆成多个提交**
3. 提交描述使用中文
4. 禁止提交构建产物（`target/`、`dist/`、`node_modules/` 等）
5. 禁止 `--no-verify` 绕过钩子

### 示例

```
feat(chat): 新增消息已读回执功能

fix(user): 修复用户头像上传大小校验失效问题

refactor(common): 统一响应格式从 ResponseVO 迁移到 Result<T>

docs(AGENTS.md): 补充 Controller 分层规范章节

chore(config): 将 @Value 分散注入改为 @ConfigurationProperties
```

## 代码风格

- Java 代码统一 **4 空格缩进**、UTF-8
- 新增类和方法必须添加 Javadoc 中文注释
- 字段使用 Lombok `@Data` 注解减少样板代码
- 集合操作优先使用 Stream API
- 判空使用 `Objects.isNull` / `StringUtils.isBlank`

## 分支命名

- 功能分支：`feature/` + 简短描述，如 `feature/chat-read-receipt`
- 修复分支：`fix/` + 简短描述，如 `fix/avatar-size-validation`
- 重构分支：`refactor/` + 简短描述，如 `refactor/unify-response-format`
