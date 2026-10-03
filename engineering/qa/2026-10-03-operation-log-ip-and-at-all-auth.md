# QA — 审计日志补记客户端 IP + @所有人 权限下沉服务端

- Change: `openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth/`
- 日期: 2026-10-03
- 分级: L4（审计控制面 + 权限语义），已过人工确认关卡

## 1. 范围

| 能力 | 内容 |
|------|------|
| C1 | 审计日志记录客户端 IP；无请求上下文记占位值 `"-"` 而非丢失或抛异常 |
| C2 | IP 取值规则统一（`X-Forwarded-For` 首段 → `getRemoteAddr()` → 占位值），限流与审计共用同一实现 |
| C3 | `@所有人` 权限下沉服务端，仅群主/管理员可发，普通成员 `CODE_2305` |

**零接口契约变更、零 DDL、零新依赖、零新 WS 帧。**

## 2. 验收口径与结果

| 口径 | 验证 | 结果 |
|------|------|------|
| 入参为空 → 自动补齐 IP | 单测 + 冒烟 | ✅ `127.0.0.1` |
| 空串同样补齐 | 单测 | ✅ |
| 显式传入不被覆盖 | 单测 | ✅ |
| 无请求上下文 → 记 `"-"` 不抛异常 | 单测 | ✅ |
| IP 获取异常不影响主流程 | 单测 | ✅ |
| 6 处调用点零改动 | 门禁 + 冒烟 | ✅ 仍为 6 处 |
| 限流与审计共用同一实现 | 门禁（断言私有 `resolveClientIp` 已删除） | ✅ |
| `X-Forwarded-For` 取首段（含多跳、含空白、病态 `,,`） | 单测 4 例 | ✅ |
| 群主发 `@所有人` → 放行 | 单测 + 冒烟 | ✅ |
| 普通成员发 `@所有人` → `2305` 且不落库 | 单测 + 冒烟 | ✅ 落库数 0→0 |
| 普通群消息**不**触发角色校验 | 单测 + 冒烟 | ✅ 防误伤 |
| 单聊带 `atAll=true` **不**被拦 | 单测 + 冒烟 | ✅ |
| 非布尔 `atAll`（`1`/`"1"`/`"true"`）不触发校验 | 单测 + 冒烟 | ✅ |
| 嵌套同名字段不算 | 单测 | ✅ |
| 非法 / 超长 / 空 `extraData` 不打断发消息 | 单测 + 冒烟 | ✅ |
| 契约零漂移 | `check-api-contract --strict` | ✅ 0 漂移 |

## 3. 实跑证据

### 3.1 单元测试：270/270（基线 237 + 新增 33）

新增：`IpToolsTest` 9 · `ExtraDataToolsTest` 14 · `OperationLogServiceImplTest` 6 · `ChatMessageServiceImplTest` +4

```
mvn -B clean test
[INFO] Tests run: 270, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`mvn -B package -DskipTests` → BUILD SUCCESS（JDK 17 目标，产物 `Spring-Boot-Version: 2.6.1` / `Build-Jdk-Spec: 17`）。

### 3.2 门禁：37/37 PASS，改造前 8/23、exit=1

`scripts/verify/verify_audit_and_at_all.mjs`，接 `pre-push` + CI。

### 3.3 变异检验（4 组，确认测试与门禁有判别力）

| 变异 | 结果 |
|------|------|
| `recordLog` 补齐改回直接透传 | 4 例转红（与红灯阶段完全一致） |
| `isAtAll` 判断取反 | 3 例转红，含 `saveMessage_withoutAtAll_doesNotCheckRole`（防误伤守卫） |
| `atAll` 校验挪到 `ROBOT_UID` 判断之外 | 门禁报出 `robot@508 atAll@277`（位置偏差） |
| 删掉 `checkGroupRole` 调用 | 2 例转红 |

> 注：第 3 项是**结构性位置断言**，单测覆盖不到（需要跨分支的控制流位置），故由门禁承担。
> 这是刻意的职责划分：**单测管行为，门禁管结构性位置**。

### 3.4 活体冒烟：16/16 PASS

`scripts/smoke/smoke_audit_and_at_all.py`（SQL 铺临时群 fixture + Redis 预热联系人缓存）。
完整输出：`2026-10-03-audit-and-at-all-smoke.txt`

关键项：

- 登录后写入 `LOGIN_SUCCESS` 审计行，`ip_address = '127.0.0.1'`（非 NULL）
- 群主发 `@所有人` → `code=0`
- 普通成员发 `@所有人` → `code=2305 无权执行此操作`，落库数 `0 → 0`
- 普通成员发普通群消息 → `code=0`（未被误伤）
- 单聊带 `atAll=true` → `code=0`
- `atAll=1`（非布尔）→ `code=0`
- 非法 `extraData` `{not json` → `code=0`

## 4. 过程中被证伪的三处

1. **`@所有人` 校验若放在 `ROBOT_UID` 判断之外会打挂机器人**——已在 tasks T2.5 与门禁双重钉住。
2. **冒烟脚本一度显示「所有消息 1002」，几乎被误判为新代码把发送链路打挂**。真实原因是 fixture 用 `LPUSH` 写入裸串 `G90062243340`，而 `RedisUtils#getQueueList` 用 **Jackson 逐元素反序列化**，裸串直接抛 `SerializationException`。正确形态是带引号的 JSON 字符串。**没有日志诊断就只能靠猜**——这次是日志给出了答案。
3. **`/group/saveGroup` 新建群强制要求上传头像**（`GroupInfoServiceImpl:197`），故冒烟改用 SQL 铺 fixture（与既有 `smoke_group_join_approval` 同一手法）。

## 5. 未运行 / 如实声明

| 项 | 原因 | 建议 |
|----|------|------|
| 前端在 `CODE_2305` 时的**提示文案** | 沙箱无 GUI；且前端仅对群主/管理员显示该入口，受影响面为零 | 本机双实例手验：构造普通成员 `@所有人` 请求看 Toast 是否可理解 |
| `X-Forwarded-For` **伪造场景** | 需要伪造请求头，冒烟环境走回环 | 手工 `curl -H "X-Forwarded-For: 1.2.3.4"` 观察审计落库值 |
| 真实反向代理下的多跳 XFF | 本机无代理 | 部署环境手验一次 |
| `mvn test` 在 CI（temurin 17）下的结果 | 本机即 JDK 17，等价 | 由 CI 确认 |

## 6. 遗留

见 `docs/system-facts.md` §14：`X-Forwarded-For` 可伪造（可信代理白名单）、`at_user_ids` 未校验成员、历史 IP 为 NULL 不可回填。

## 7. 结论

**通过。** 验收口径逐条满足，单测 270/270、冒烟 16/16、门禁 37/37、变异 4 组全被捕获，契约零漂移。§5 四项未运行项如实登记，**未谎报为通过**。