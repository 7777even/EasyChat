# Proposal — 后端集成测试层（无 DB 方案 A）

- 创建日期: 2026-10-13
- 效率等级: **L3**（测试基建 + 新增测试目录；不动生产代码、不动 pom、不动 CI 结构、不动接口契约）

## Why

后端 59 个测试文件**全部**是「直接 `new` 出实现类 + `@MockBean` 式 mock Mapper」的单元测试，实测全仓 `@SpringBootTest` / `@MybatisTest` **零命中**。这让三层行为完全没有自动化：

1. **拦截器链**（`@GlobalInterceptor` 的 `checkAdmin` / `checkRateLimit` / `checkLogin`）—— 此前只有 `GlobalInterceptorAnnotationContractTest` 的**反射式结构断言**（"该注解在不在"），没有任何**行为**断言。而历史事故恰好集中在这层：`#30`「`@所有人` 权限仅在客户端生效」是**服务端根本没校验**，结构断言完全测不出。
2. **参数校验的真实触发** —— 已登记为遗留 **#32**：`MockMvcBuilders.standaloneSetup` 不注册 `MethodValidationPostProcessor`，故类级 `@Validated` + 方法参数注解（`sendEmailCode(@NotEmpty String email, …)`）在现有单测环境下**根本不触发**，只能写结构断言；`@ModelAttribute` 绑定的 `@Valid` 也只在真容器里才走 `ModelAttributeMethodProcessor`。
3. **Spring 上下文能否在测试环境无依赖启动** —— 这本身是后续所有集成测试的前提，当前**从未被验证过**。

## What Changes

- 后端:
  - 新增 `easychat-java/src/test/java/com/easychat/integration/`（**新包，与既有单元测试包隔离**）
  - 新增 `easychat-java/src/test/resources/application-test.properties`（关闭 Flyway、指向不可达数据源、排除 Redis 相关自动装配）
  - 新增拦截器链行为测试（`checkAdmin` / `checkRateLimit` / 未标注端点）
  - 新增参数校验**真容器**行为测试（覆盖遗留 #32 的盲区）
  - **生产代码零改动**（ADR-001）
- 前端: 无改动
- 数据库: **无表结构 / 字段变更**；测试上下文**不连真库**（Mapper 一律 `@MockBean`）
- pom.xml: **无改动**（`spring-boot-starter-test` 已在 test scope，无需 Testcontainers —— 人工选定方案 A 即为规避该依赖）
- CI: **无结构改动**（`backend` job 已在跑 `mvn test`，新增测试自动纳入）

### 明确不在本次范围（见 design.md ADR-001）

- ❌ MyBatis XML 真实 SQL 语义 / 事务边界（`afterCommit` 推帧 20）→ 属方案 B，需活库或 Testcontainers
- ❌ 前端 E2E
- ❌ 任何生产代码改动

## Capabilities

- **C1**: 认证与限流守卫的**行为**可被自动化验证 —— 非管理员访问管理端必须被拒（`CODE_1003`）、未登录端点超阈值必须被限（`CODE_1001`）、未标注 `@GlobalInterceptor` 的端点不拦截。
- **C2**: 参数校验在**真实 Web 上下文**中触发 —— `@ModelAttribute` + `@Valid` 的字段级文案、方法级 `@Validated` + 参数注解，两条路径各自可验证（闭环遗留 #32）。
- **C3**: Spring 测试上下文可在**无 MySQL / 无 Redis** 的环境下启动 —— 使上述测试可在本机与 CI 稳定复现，并为后续方案 B 预留同一套基建。

## Impact

- **对外接口**: 零变更（`check-api-contract --strict` 应保持 0 漂移）
- **存量数据**: 零影响（不连库、不执行迁移）
- **性能 / 安全**: 零影响（测试代码不进产物；`application-test.properties` 仅在 test scope）
- **回退方案**: 删除 `src/test/java/com/easychat/integration/` 与 `src/test/resources/application-test.properties` 即可，生产代码零牵连
- **新增依赖**: 无

---

## ☐ 人工确认关卡

> 本提案经 _________（角色/姓名） 于 <YYYY-MM-DD> 确认，允许进入 design 阶段。
>
> - [ ] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估