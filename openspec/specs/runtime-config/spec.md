# Spec — 运行时配置

## ADDED Requirements

### Requirement: 公共配置基线与环境分离

系统的运行配置按「公共基线 + 环境 profile」两层加载，公共基线跨环境一致，敏感项以环境变量占位声明。

#### Scenario: 默认启动（零配置）

- **WHEN** 开发者 clone 后未设置任何环境变量，直接 `mvn spring-boot:run`
- **THEN** 以 `dev` profile 启动，加载 `application.properties` + `application-dev.properties`
- **AND** 服务正常监听 5050 / 5051，行为与配置外置前一致

#### Scenario: 敏感项在公共基线中不出现真实凭据

- **WHEN** 查阅入库的 `application.properties`
- **THEN** DB 密码、TURN 凭据、管理员邮箱均以 `${ENV_VAR:默认值}` 形式声明
- **AND** 不含任何真实生产凭据

#### Scenario: 环境专属项不进入公共基线

- **WHEN** 查阅 `application.properties`
- **THEN** 不含 `easychat.turn.url` / `easychat.turn.username` / `easychat.turn.credential`
- **AND** 这些键只存在于 profile 文件中（`CallService` 的 `@Value` 默认值为空，缺失时不影响启动）

---

### Requirement: 环境 profile 差异化

`dev` 与 `prod` 两份 profile 承载各自环境的取值，同名键在 profile 中覆盖公共基线。

#### Scenario: dev profile 提供本地联调所需的公共 TURN

- **WHEN** 以 `dev` profile 启动
- **THEN** `easychat.turn.url` / `username` / `credential` 取公共测试中继值，便于本机双实例联调音视频通话

#### Scenario: prod profile 不含公共 TURN 凭据

- **WHEN** 以 `prod` profile 启动
- **THEN** `easychat.turn.url` 为空，信令引导帧下发的 `iceServers` 退化为仅 STUN
- **AND** 对称 NAT 场景通话不通（有意降级，生产必须自建 coturn 并注入凭据）

#### Scenario: prod profile 不含 DB 默认密码

- **WHEN** 以 `prod` profile 启动且未注入 `SPRING_DATASOURCE_PASSWORD`
- **THEN** 连接被拒，日志出现 `using password: NO`
- **AND** 该失败为**预期行为**——生产禁止使用任何烘焙在版本库里的凭据

#### Scenario: 注入后 prod profile 正常启动

- **WHEN** 以 `prod` profile 启动并注入 `SPRING_DATASOURCE_PASSWORD`
- **THEN** 正常监听 5050 / 5051，无 placeholder 解析错误

---

### Requirement: 凭据经环境变量注入且不入库

真实凭据通过系统环境变量注入；`.env` 作为开发者本地载体不入库，`.env.example` 作为模板入库。

#### Scenario: 仓库不含真实凭据文件

- **WHEN** 查阅版本库
- **THEN** 存在 `.env.example`（仅占位值与说明）
- **AND** 不存在被追踪的 `.env`

#### Scenario: Spring Boot 2.6 不自动读取 .env

- **WHEN** 开发者仅创建 `.env` 后直接 `mvn spring-boot:run`
- **THEN** `.env` 中的值**不会**被自动加载（自动导入是 Spring Boot 3.0+ 的 `spring.config.import` 特性）
- **AND** 必须经 `docker compose --env-file .env` 或 `set -a; . ./.env; set +a` 注入

#### Scenario: 容器环境通过 compose 注入且默认 prod profile

- **WHEN** 执行 `docker compose up -d`
- **THEN** backend 的 `SPRING_PROFILES_ACTIVE` 缺省为 `prod`，忘加 `--env-file` 时也不会静默跑在 dev profile
- **AND** 同一份 `application-prod.properties` 无需为容器做任何修改

#### Scenario: backend 不可多实例

- **WHEN** 对 backend 执行 `docker compose up --scale backend=2`
- **THEN** 通话建立失败（`CallService` 房间注册表为进程内 `ConcurrentHashMap`，不跨实例）
- **AND** 该约束须在 compose 注释、`CallService` 类注释与 README 中显式说明

---

## 门禁

| 脚本 | 阻断条件 |
|------|----------|
| `scripts/verify/verify_no_hardcoded_secret.mjs` | 公共基线含裸凭据 / 含 `easychat.turn.*` / 默认 profile 非 dev；dev 缺公共 TURN 或缺本地 DB 密码；prod 含公共 TURN 凭据或 DB 密码有默认可用值或管理员邮箱含真实地址；`.env` 未被 gitignore / 已入库 / `.env.example` 缺失 |
