# Spec Delta — 运行时配置外置

- 关联 Tasks: 2026-10-02-config-externalization/tasks.md
- 创建日期: 2026-10-02

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

- **WHEN** 以 `prod` profile 启动
- **THEN** `spring.datasource.password` 无可用默认值，必须由环境变量或 `.env` 注入，否则启动失败属预期行为

---

### Requirement: 凭据经环境变量注入且不入库

真实凭据通过系统环境变量注入；`.env` 作为开发者本地载体不入库，`.env.example` 作为模板入库。

#### Scenario: 仓库不含真实凭据文件

- **WHEN** 查阅版本库
- **THEN** 存在 `.env.example`（仅占位值与说明）
- **AND** 不存在被追踪的 `.env`

#### Scenario: 容器环境通过 compose 注入

- **WHEN** 执行 `docker compose up -d`
- **THEN** compose 以环境变量覆盖 DB 连接、Redis host、项目目录
- **AND** 同一份 `application-prod.properties` 无需为容器做任何修改

---

## REMOVED Requirements

### Requirement: 单一 application.properties 承载全部环境配置

**移除原因**: 该基线把 DB 凭据、TURN 公共凭据、管理员邮箱、落盘根目录硬编码进版本库，且无法区分开发与生产，改配置即改生产基线。

**替代方案**: 由「公共配置基线与环境分离」+「环境 profile 差异化」+「凭据经环境变量注入且不入库」三条 Requirement 取代。

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 | ADDED: 公共配置基线与环境分离 | 1.1, 1.2, 4.1 |
| C2 | ADDED: 环境 profile 差异化 | 2.1, 2.2, 4.2 |
| C3 | ADDED: 凭据经环境变量注入且不入库 | 3.1, 3.2, 4.3 |
| （被取代） | REMOVED: 单一 application.properties 承载全部环境配置 | 1.1 |
