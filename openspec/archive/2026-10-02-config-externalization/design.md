# Design — 运行时配置外置

- 关联 Proposal: 2026-10-02-config-externalization/proposal.md
- 创建日期: 2026-10-02

## 1. 架构设计

Spring Boot 原生 profile 机制 + 占位符默认值。不引入新依赖、不引入配置中心。

```
配置优先级（低 → 高）
  application.properties          公共基线，跨环境一致，敏感项写成 ${ENV:默认值}
    ↓ 被 profile 覆盖
  application-${profile}.properties   dev / prod
    ↓ 被环境变量覆盖（Spring Environment relaxed binding）
  系统环境变量 / .env 注入的 SPRING_DATASOURCE_PASSWORD 等
```

profile 由 `spring.profiles.active` 决定，默认 `dev`（保证零配置 clone 即可 `mvn spring-boot:run`）；容器/生产用 `SPRING_PROFILES_ACTIVE=prod` 切换（`docker-compose.yml` 已按此写法准备）。

```
easychat-java/src/main/resources/
  application.properties        公共基线（入库）
  application-dev.properties    本地开发值（入库，含公共 TURN 联调凭据）
  application-prod.properties   生产模板（入库，TURN 留空）
```

仓库根：
```
.env.example    入库：凭据模板，含注释与安全提示
.env            不入库（.gitignore）
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 配置文件 | 拆 3 份 + 敏感项占位 | 不涉及 Java 代码 |
| `.gitignore` | 新增 `.env` | — |
| `.env.example` | 新增 | — |

**无 Java 代码改动**（`AppConfig.projectFolder` / `EasyChatProperties` / `TurnProperties` 现有 `@Value` 与 `@ConfigurationProperties` 绑定键名全部保持不变，只换值的来源）。

### 前端改动

无。

## 2. 接口设计

无对外接口变更。`Result<T>` 包络、`/api/` 前缀、错误码分段、HTTP 端口 5050 / WS 端口 5051 全部不变。

## 3. 数据模型

无表结构/字段变更，不产出迁移脚本。

## 4. 安全设计

- 敏感项（`spring.datasource.password`、`easychat.turn.*`、`easychat.admin-emails`）改为 `${ENV:默认值}`，默认值本身在 prod profile 中为空/占位，不含真实凭据
- `.env` 加 `.gitignore`；`.env.example` 只含占位值与说明
- `pre-commit` 黑名单本已拦 `*.log`；本变更额外确保 `.env` 不会被 `git add -A` 带入
- 已知残留（**不在本变更范围**，记入批次 5）：`application-dev.properties` 中的公共 TURN 凭据属第三方公开测试服，仅供本机联调，README §7 已警示

## 5. ADR

### ADR-001: 用 Spring 原生 profile，不引入 Nacos/Apollo

- 状态: 已接受
- 上下文: 配置外置有两条路——Spring profile 文件，或配置中心。配置中心能热更新、支持多环境统一治理，但要新增生产依赖 + 独立部署 + 运维成本。
- 决策: 用 Spring 原生 profile 三段 + 占位符默认值。
- 后果: 正面——零新增依赖、`mvn spring-boot:run` 零配置可跑、CI 无需任何 secret 即可编译测试。负面——改配置仍需重启、无法热更新、多实例共享配置要靠部署脚本注入环境变量。当前单体部署规模下这个代价可接受。

### ADR-002: 公共基线的敏感项保留「可用默认值」而非强制必填

- 状态: 已接受
- 上下文: 若把 DB 密码写成 `${DB_PASSWORD}`（无默认值），任何没设该变量的环境会在启动时抛 `IllegalArgumentException: Could not resolve placeholder`，把「缺配置」变成「启动崩溃」，反而更难自举。
- 决策: 公共基线里用 `${SPRING_DATASOURCE_PASSWORD:root}` 这种带默认值写法，dev profile 保持与原值一致；prod profile 覆盖为空。
- 后果: 正面——零配置启动不破、dev 环境行为与改动前完全一致（零回归风险）。负面——默认值 `root` 仍会进版本库，但它现在只是「本地开发默认」而非「生产凭据」，且 prod profile 显式置空，实际风险远低于原状。

### ADR-003: `.env` 靠 Spring 原生解析之外的方式注入

- 状态: 已拒绝（本次不实现）
- 上下文: Spring Boot 2.6 不自动读 `.env`（那是 Spring Boot 3.0+ 的 `spring.config.import` 特性）。要让 `.env` 生效需额外引 `dotenv-java` 或用 shell `source`。
- 决策: 本变更**不引新依赖**。`.env` 文件仅作为「给开发者 copy 成系统环境变量 / 供 compose `--env-file` 使用」的载体；实际注入由 `docker compose --env-file .env` 或部署脚本 export 完成。
- 后果: 正面——不新增生产依赖，守住 AGENTS §8。负面——纯 `mvn spring-boot:run` 不会自动读 `.env`，README 需写明这一点（已写入）。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| profile 文件名写错导致未被加载 | 中 | 高（配置静默失效，回落公共基线） | 启动后用 `/admin/sysSetting` 或 actuator 之外的方式核对；本变更在 QA 中实跑 `dev` 与 `prod` 两个 profile 各一次并比对生效值 |
| 遗漏某敏感项仍硬编码 | 中 | 高 | `verify` 类脚本新增一条敏感项扫描（`scripts/verify/verify_no_hardcoded_secret.mjs`）纳入 CI |
| prod profile TURN 留空导致通话失败 | 高 | 中 | 这是**有意的安全降级**：留空即仅 STUN，对称 NAT 通话不通但不会把媒体经第三方中继。README §7 + prod profile 注释双重警示 |
| 存量本地开发环境行为变化 | 低 | 中 | ADR-002 保留与原值一致的 dev 默认值，dev 环境行为不变 |

## 7. 依赖与前提

- 前提：`docker-compose.yml` 已按 `SPRING_PROFILES_ACTIVE` / `PROJECT_FOLDER` 环境变量注入编写（批次 1 骨架已交付），本变更与之对齐
- 前提：Spring Boot 2.6.1 原生 profile 机制，无新依赖
- 无前置 Change
