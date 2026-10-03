# QA — 密码变更后会话失效 + 邮箱验证码真实投递

- Change: `openspec/changes/2026-10-03-password-session-and-mail/`
- 日期: 2026-10-03
- 分级: L4（认证鉴权 + 生产依赖新增），已过人工确认关卡

## 1. 范围

覆盖 proposal 三项能力 C1/C2/C3，不含明确裁剪掉的 4 项（见 §6）。

| 能力 | 内容 |
|------|------|
| C1 | 改密 / 找回密码成功后该用户**全部端** Redis Token 失效 + 推 `FORCE_OFF_LINE(7)` |
| C2 | 邮箱验证码 SMTP 真实投递；未配置时 **fail-closed**（`CODE_1002`），日志中无验证码明文 |
| C3 | 未登录端点按**客户端 IP** 限流，不再因缺 token 头而失效 |

**零接口契约变更、零表结构变更。**

## 2. 验收口径与执行结果

| 口径 | 验证方式 | 结果 |
|------|----------|------|
| 改密成功后全部端 Token 失效 | 单测 + 冒烟（Redis 键 + 接口） | ✅ |
| 改密成功推 FORCE_OFF_LINE | 单测（`messageHandler.sendMessage`） | ✅（未做 GUI 端到端，见 §5） |
| 找回密码成功后全部端 Token 失效 | 单测 + 冒烟 | ✅ |
| 改密失败不吊销会话 | 单测（含守卫）+ 冒烟 | ✅ |
| 重置失败（码错/过期/邮箱未注册）不吊销 | 单测（含守卫）+ 变异证明 | ✅ |
| 验证码不经日志 | 门禁 + 单测 + 冒烟（扫日志正则） | ✅ |
| 未配 SMTP → `CODE_1002` | 单测 + 冒烟 | ✅ |
| 邮件主题不含用户 email | 单测 + 变异 | ✅ |
| 未登录端点按 IP 限流 | 单测 + 冒烟（第 61 次被拒） | ✅ |
| 登录态端点仍按 token 限流 | 单测 | ✅ |
| 契约零漂移 | `check-api-contract --strict` | ✅ 0 漂移 |

## 3. 实跑证据

### 3.1 单元测试：232/232（基线 200 + 新增 32）

```
mvn -B test
[INFO] Tests run: 13, Failures: 0 ... GlobalOperationAspectTest
[INFO] Tests run: 58, Failures: 0 ... UserInfoServiceImplTest
[INFO] Tests run: 13, Failures: 0 ... MailServiceTest
[INFO] Tests run: 232, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 3.2 TDD 红灯阶段（**先红后绿，非事后补断言**）

- `GlobalOperationAspectTest` 首跑：**13 例 / 5 红**，5 红恰为 `rateLimit_*`，其余 8 例（`checkLogin`/`checkAdmin` 既有行为）为绿 → 证明脚手架正确、红因是限流未实现。
  原始输出：`2026-10-03-password-session-and-mail-tdd-red.txt`
- `UserInfoServiceImplTest` 追加用例首跑：**56 例 / 3 红**，3 红恰为两个成功路径的会话失效断言 + `pushForceOffLine`。

### 3.3 变异检验：证明测试与门禁**有判别力**

| 变异 | 结果 |
|------|------|
| 限流「不 return」改回 `return` | 同样 5 例转红 |
| 会话吊销挪到密码写入之前 | 守卫用例 `codeNotFound_doesNotInvalidateSessions` 转红 |
| 注释掉 `forceOffLine` | 恰好 1 例转红 |
| 邮件未配置改打日志放行 | 3 例转红 |
| 邮件主题拼入 email | 1 例转红（报「主题泄露了用户邮箱」） |
| 去掉验证码纯数字校验 | 1 例转红 |
| 失败消息带上验证码 | 1 例转红（报「异常消息泄露了验证码」） |
| `mutation_password_session.cjs` 12 条 | **12/12 CAUGHT**，沙箱基线 == 真实基线，exit=0 |

### 3.4 活体冒烟：29/29 PASS

`scripts/smoke/smoke_password_session.py`，后端 `mvn spring-boot:run` + MySQL + Redis 实跑。
完整输出：`2026-10-03-password-session-smoke.txt`

关键项：

- 未登录端点连打，**第 61 次**被限流拦截（阈值 60）
- `sendEmailCode` 未配 SMTP → `code=1002 msg=邮件服务未配置，无法发送验证码`
- 后端日志正则扫「验证码已生成…code=NNNNNN」→ **命中 0 条**
- 改密后 A 的**两个端** token 键均从 Redis 删除，两个端请求均返回 `2001`
- 对照组 B 会话不受影响（`code=0`）
- 改密失败（`2103`）后旧 token 仍可用（`code=0`）
- 找回密码成功后旧 token → `2001`；同一验证码二次使用被拒
- 收尾把 A 的密码还原为原口令

### 3.5 门禁：35/35 PASS，变异 12/12

- `verify_password_session.mjs`：**35/35**，exit=0。改造前首次实跑 **9/29、exit=1**（24 项红对应真实缺陷）。
- `verify_no_hardcoded_secret.mjs`：19 → **25/25**（补 prod 邮件五键占位符且无默认可用值）。
- 回归 11 个门禁全绿：`check-openspec-hygiene` / `check-api-contract --strict` / `check-ipc-registration --strict` / `verify_ws_frame_parity` / `verify_mapper_params` / `verify_file_type_content_type` / `verify_no_hardcoded_secret` / `verify_password_handoff` / `verify_password_session` / `verify_call_core` / `verify_virtual_core`。

### 3.6 构建

`mvn -B package -DskipTests` → BUILD SUCCESS，产物 `easychat-1.0.jar`（53,487,534 字节）。
注意产物由 `spring-boot-maven-plugin:2.2.6.RELEASE` repackage，与 parent `2.6.1` 倒挂——**已知技术债，本批不动**，已记入遗留。

## 4. 文档同步

`docs/system-facts.md` 已回写，并**纠正两处历史事实失真**：

1. 「修改密码…服务端校验旧密码 MD5 匹配」→ 实为 **BCrypt + MD5 双验证**。
2. 「修改密码…成功后关闭 WS 强制重登」→ **该行为在本批之前从未实现**（此前只写库 + 记日志）。
3. 「未配置邮件服务时验证码写日志」→ 已作废，改为 fail-closed。

新增事实条目：邮件服务配置分层、未登录端点限流键规则。

## 5. 未运行 / 如实声明

| 项 | 原因 | 建议 |
|----|------|------|
| FORCE_OFF_LINE 帧的**客户端 GUI 表现**（用户是否真被踢出登录界面） | 沙箱无 GUI | 本机双实例手验：改密后观察对端是否自动回到登录页 |
| SMTP **真实投递**（配了真 SMTP 后邮件是否到达、渲染是否正常） | 本机未配 SMTP，且不允许用真实凭据 | 部署环境配 `SPRING_MAIL_*` 后手验一次 |
| 前端找回密码页在 `CODE_1002` 时的提示表现 | 沙箱无 GUI | 观察 `Request.js` 错误分支的 Toast 文案是否可理解（预期「邮件服务未配置，无法发送验证码」） |
| `mvn test` 在 **JDK 8**（CI/Docker 版本）下是否全绿 | 本机 `mvn -v` 为 **JDK 17**，与 CI 的 temurin 8 不一致 | 见遗留第 5 项 |

## 6. 遗留（本批明确不做，已登记）

| 项 | 原因 | 后续 |
|----|------|------|
| `email_verify_code` 补 `try_count` 失败次数限制 | 属 DB 结构变更（L4），刻意与鉴权改造解耦（ADR-005） | 独立 Change，需 migration-013 |
| `operation_log.ip_address` 恒为 `null`（6 处调用全传 null） | 需 AOP 取 `RequestContextHolder`（横切面） | 独立 Change |
| `@所有人` 权限下沉服务端（`saveMessage` 校验 role） | 涉权限语义（L4） | 独立 Change |
| `verify_schema_drift.mjs` 连库版门禁 | 独立工程化 Change | 本会话下一批 |
| JDK 版本三方不一致（pom 1.8 / CI 8 / 本机 17） | 依赖与框架面（L4） | 独立 Change |

## 7. 结论

**通过。** 验收口径逐条满足，单测 232/232、冒烟 29/29、门禁 35/35、变异 12/12 + 6 组手工变异全部被捕获，契约零漂移。§5 四项未运行项如实登记，**未谎报为通过**。