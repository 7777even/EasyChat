# QA — 运行时配置外置

- Change: `openspec/changes/2026-10-02-config-externalization/`
- 执行日期: 2026-10-02
- 环境: Windows / JDK 17（编译器 target 1.8）/ Maven / MySQL 3306 + Redis 6379 均在库
- 结论: **通过**

## 1. 范围与验收口径

| 项 | 验收口径 |
|----|----------|
| 公共基线 | 无裸凭据；敏感键全为 `${ENV:默认值}`；不含 `easychat.turn.*`；默认 profile = dev |
| dev profile | 取值与变更前**完全一致**（零行为变化） |
| prod profile | TURN 留空、无公共凭据、DB 密码无默认可用值、管理员邮箱无真实地址 |
| 凭据载体 | `.env` 不入库、`.env.example` 入库 |
| 契约零变更 | `check-api-contract --strict` 仍 exit 0 |
| 构建 | `mvn -B clean package -DskipTests` exit 0 |
| 行为零回归 | 活体冒烟 `probe_admin.py` 管理员鉴权结果与变更前一致 |

## 2. 实际执行的命令与结果

### 2.1 TDD 红 → 绿（任务 1.1 / 4.1）

先写门禁 `scripts/verify/verify_no_hardcoded_secret.mjs`，在**未改造**的配置上跑（应为红）：

```
node scripts/verify/verify_no_hardcoded_secret.mjs
  [PASS] 公共基线 application.properties 存在
  [FAIL] 公共基线默认 profile = dev                → 实际为 undefined
  [FAIL] 公共基线不含 easychat.turn.url            → turn:turn.anyfirewall.com:443?transport=tcp
  [FAIL] 公共基线不含 easychat.turn.username       → guest
  [FAIL] 公共基线不含 easychat.turn.credential     → guess
  [FAIL] 公共基线敏感键为占位符形式：spring.datasource.username → root
  [FAIL] 公共基线敏感键为占位符形式：spring.datasource.password → root
  [FAIL] 公共基线 easychat.admin-emails 为占位符   → test@qq.com
  [FAIL] application-dev.properties 存在（承载本地联调值） → 文件缺失
  [FAIL] application-prod.properties 存在（生产模板入库） → 文件缺失
  [FAIL] .gitignore 已忽略 .env
  [FAIL] .env.example 存在（模板入库）
  ===== 结论：2/15 通过 =====   exit=1
```

改造后转绿：

```
  ===== 结论：19/19 通过 =====   exit=0
```

### 2.2 构建

```
mvn -B -q clean package -DskipTests   → exit=0
target/easychat-1.0.jar  52,714,182 bytes
```

### 2.3 活体启动：三组 profile 场景（任务 4.2）

**场景 A — dev profile，零配置**

```
java -jar target/easychat-1.0.jar
  The following profiles are active: dev
  Tomcat started on port(s): 5050 (http) with context path '/api'
  Netty 启动成功, 端口:5051
  InitRun → "服务启动成功，可以开始愉快的开发了"
```

> `InitRun` 用 `logger.error` 打成功信息（日志级别误用，**变更前既有**，非本次引入），且控制台按 GBK 解码 UTF-8 故显示乱码。`dataSource.getConnection()` 成功即证明
> `spring.datasource.url=${...:jdbc:mysql://...?a=1&b=2}` 这种**默认值内含 `?` `&` `:` 的长 URL 占位符解析正常**——这是本变更最大的解析风险点。
> 全程无 `Could not resolve placeholder`。

**场景 B — prod profile，未注入口令（应失败，且是设计内的失败）**

```
SPRING_PROFILES_ACTIVE=prod java -jar target/easychat-1.0.jar
  The following profiles are active: prod
  Caused by: com.mysql.cj.exceptions.CJException:
             Access denied for user 'root'@'localhost' (using password: NO)
  进程退出
```

> 关键证据 `using password: NO` —— prod 的 `spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:}` 解析为空，
> 即**拒绝使用任何烘焙在版本库里的凭据**。这正是 spec-delta「prod profile 不含 DB 默认密码」的验收点。
> 非 placeholder 解析失败。

**场景 C — prod profile，注入口令（应正常起）**

```
SPRING_PROFILES_ACTIVE=prod SPRING_DATASOURCE_PASSWORD=root java -jar target/easychat-1.0.jar
  The following profiles are active: prod
  Tomcat started on port(s): 5050 (http) with context path '/api'
  Netty 启动成功, 端口:5051
```

> 证明注入链路端到端可用，且 prod profile 本身无其他缺失配置。

### 2.4 行为零回归（活体冒烟）

```
python scripts/smoke/probe_admin.py
  test@qq.com           admin=True    POST /admin/report/loadReport → http=200 code=0
  karina7710@test.com   admin=False   POST /admin/report/loadReport → http=400 code=1003
```

> 证明 `easychat.admin-emails=test@qq.com` 在 dev profile 下**仍然生效**（超级管理员判定未因配置外置而漂移），
> `GlobalOperationAspect` 的 `checkAdmin` 对非管理员仍正确拒绝。

### 2.5 全量门禁

```
node scripts/check-api-contract.mjs --strict        → exit=0
node scripts/check-ipc-registration.mjs --strict    → exit=0
node scripts/check-openspec-hygiene.mjs             → exit=0
node scripts/verify/verify_mapper_params.mjs        → exit=0
node scripts/verify/verify_password_handoff.mjs     → exit=0
node scripts/verify/verify_no_hardcoded_secret.mjs  → exit=0
node scripts/verify/verify_virtual_core.mjs         → exit=0
node scripts/verify/verify_call_core.mjs            → exit=0
```

### 2.6 其他

```
docker compose config --quiet   → exit=0
npm run build（批次 1）          → built in 39.14s, 0 error
```

## 3. 未运行 / 未覆盖项（不谎报）

| 项 | 原因 | 后续 |
|----|------|------|
| `mvn test` 单元测试 | 本变更零 Java 代码改动，未触发回归面；但未实跑 | CI `backend` job 已配置 `mvn -B test`，首推即执行 |
| `.env` 经 `docker compose --env-file` 实际注入 | 需先备真实 MySQL/Redis 凭据与真实 TURN，属生产部署动作 | 生产部署时执行 |
| 自建 coturn 下的通话连通性 | 需外部 TURN 服务，沙箱无 | 随批次 5 一并验收 |
| `application-prod.properties` 完整生产启动 | 缺真实生产 DB/Redis/文件卷 | 生产部署时执行 |
| GUI 相关（本次无 UI 改动） | — | — |

## 4. 结论

**通过。** 19 项门禁断言全绿，三组 profile 启动场景行为符合 spec-delta，冒烟证明管理员鉴权零回归，构建 0 error，契约零变更。

`easychat.sql` 与迁移脚本**无需改动**（无表结构/字段变更）—— 已核对确认，非遗漏。

## 5. 证据文件

- 本文件即证据主体（含全部终端输出摘录）
- 原始启动日志（未入库，用完即删）：`%TEMP%/ec-dev.log`、`%TEMP%/ec-prod.log`、`%TEMP%/ec-prod2.log`
