# Design — 后端集成测试层（无 DB 方案 A）

- 关联 Proposal: `2026-10-13-backend-integration-test-layer/proposal.md`
- 创建日期: 2026-10-13

## 1. 架构设计

```
src/test/java/com/easychat/
├─ service/impl/*Test.java              （既有：直接 new 实现类 + mock Mapper，共 59 文件）
└─ integration/                          （新增：@SpringBootTest + @AutoConfigureMockMvc）
   ├─ IntegrationTestBase.java          公共基类：@SpringBootTest + @ActiveProfiles("test")
   ├─ InterceptorChainTest.java         C1：checkAdmin / checkRateLogin / checkLogin 行为
   ├─ AdminAccessBehaviourTest.java     C1：非管理员访问管理端 → CODE_1003
   ├─ RateLimitBehaviourTest.java       C1：未登录端点超阈值 → CODE_1001
   └─ ParamValidationBehaviourTest.java C2：@Valid / @Validated 真容器触发（遗留 #32）

src/test/resources/application-test.properties   （新增：关闭 Flyway / 不可达数据源）
```

调用链：`MockMvc.perform(...)` → 真实 `DispatcherServlet` → 真实 `HandlerMethodArgumentResolver`
→ 真实 `MethodValidationPostProcessor`（AOP）→ 真实 `GlobalOperationAspect`（AOP）→ Controller
→ Service（Mapper 被 `@MockBean` 拦截，不落库）

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | **零改动** | 路由 + @Valid + 调 Service |
| Service | **零改动** | 业务规则 + 事务边界 |
| Mapper | **零改动**（测试中 `@MockBean`） | 数据访问 |
| Aspect | **零改动**（本 Change 就是给它补行为断言） | `GlobalOperationAspect` 的 checkAdmin / checkRateLimit |
| 测试 | 新增 `integration/` 包 + test properties | 只断言行为，不复制业务规则 |

## 2. 接口设计

**无对外接口变更。** 本 Change 只新增测试，不新增 / 修改任何端点、DTO、VO、错误码。

## 3. 数据模型

**无表结构 / 字段变更。** 测试上下文**不执行 Flyway 迁移、不连接真实数据库**（见 ADR-002）。

## 4. 安全设计

- 本 Change 验证的是**安全控制本身**：`checkAdmin`（管理端越权）、`checkRateLimit`（未登录端点爆破）
- 测试用例中不得写入任何真实凭据；`application-test.properties` 的口令必须是**占位值**（且不进产物）
- 需与既有门禁 `verify_no_hardcoded_secret.mjs` 复核：`src/test/resources/**` 是否在其扫描范围内，
  若在，则占位口令也需写成 `${...:}` 形式而非裸值（**实施时实测确认，不假设**）

## 5. ADR（架构决策记录）

### ADR-001: 只做无 DB 的方案 A，真库验证留给方案 B

- 状态: **已接受**（人工选定）
- 上下文: 要覆盖 MyBatis XML 真实 SQL 语义与事务边界，需要真实数据库。可选 Testcontainers（自包含）
  或直连活库。**实测本机 Docker daemon 未运行**（`dockerDesktopLinuxEngine` 管道不存在），
  Testcontainers 无法在本地红-绿迭代；而 `scripts/smoke/*.py` 已有 16 个活体冒烟脚本覆盖真库链路。
- 决策: 本次只做不依赖 DB 的集成测试层（拦截器链 + 参数校验 + 上下文可启动性）。
- 后果:
  - 正面: 本机与 CI 都能稳定复现；零新增依赖；不动 pom 与 CI 结构
  - 负面: **MyBatis XML 的真实 SQL 语义与事务边界仍无自动化** —— 这是历史两次
    「`<if>` 全不命中 → SET 为空 → `UPDATE t where` → 全部 1002」事故所在的层，仍依赖 smoke 脚本兜底

### ADR-002: Mapper / Redis / Mail 一律 `@MockBean`，不接真依赖

- 状态: 已接受
- 上下文: `@SpringBootTest` 会加载完整 `application.properties`，其中数据源指向真实 MySQL、
  Flyway 会尝试连库执行迁移。
- 决策: 新增 `application-test.properties`：`spring.flyway.enabled=false`、
  数据源指向**不可达**地址（HikariCP 惰性建连，Mapper 已 mock 故永不建连）、排除 Redis 自动装配；
  测试类按需 `@MockBean` Mapper / `RedisUtils` / `MailService`。
- 后果: 测试不依赖任何外部进程；**代价是数据源「不可达」这一前提必须实测验证**
  （见 tasks 阶段一 T2）—— 若 HikariCP 或某个自动装配在启动期就要求建连，方案需调整

### ADR-003: 参数校验必须用**真容器**，不得退回 `standaloneSetup`

- 状态: 已接受
- 上下文: 遗留 #32 已确认 `standaloneSetup` 不注册 `MethodValidationPostProcessor`，
  故类级 `@Validated` + 方法参数注解在单测层**恒不触发**。
- 决策: 用 `@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc`，
  它基于 `WebApplicationContext`，**会**注册 `MethodValidationPostProcessor`；
  测试断言 HTTP 响应体（`code` / `message`），而非反射检查注解是否还在。
- 后果: 遗留 #32 从「维持登记的盲区」变为**真容器行为覆盖**；但代价是本层测试对
  Spring 版本升级更敏感（属可接受，Spring Boot 由 parent 统一管理）

### ADR-004: 断言必须打真实响应体，不复用「反射查注解」那套

- 状态: 已接受
- 上下文: 既有 `GlobalInterceptorAnnotationContractTest` 已做结构断言，本 Change 若也做结构断言则**零增量**。
- 决策: 一律断言 `response.getStatus()` 与响应 JSON 的 `code` / `message`。
- 后果: 能覆盖「注解在但守卫写反」这类结构断言**照不出**的缺陷（`#30` 的形态）

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| `@SpringBootTest` 启动时被 Flyway / Redis / 数据源拖垮，方案不可行 | 中 | 高 | **tasks 阶段一 T1/T2 先做「最小可启动」验证**，跑不通就停并回到 proposal（不硬写后续任务） |
| `application-test.properties` 未生效（profile 未激活），测试连了真库 | 低 | **高**（可能污染活库） | 启动时断言数据源 URL 指向测试值；`spring.flyway.enabled=false` 双重保险 |
| 新增测试显著拖长 `mvn test`，拖慢 CI | 中 | 中 | 只加 3~4 个测试类；用 `@SpringBootTest` 的**上下文缓存**（同配置只起一次） |
| 端点数多，逐个补行为用例导致工期膨胀 | 中 | 中 | **只补高风险切片**（管理端越权 / 未登录限流 / 两条校验路径），不做全量矩阵 |
| 与既有 59 个单元测试的包路径混淆 | 低 | 低 | 新增独立包 `integration/`，并在 design 中写明命名约定 |
| 测试代码里的占位口令触发 `verify_no_hardcoded_secret.mjs` | 中 | 低 | 实施时实测该门禁扫描范围；必要时写成 `${ENV:}` 形式 |

## 7. 依赖与前提

- 前提: `spring-boot-starter-test` 已在 test scope（**已实测**，见 pom.xml）
- 前提: 本机 JDK 17 + Maven 可用（**已实测**）
- 前提: 人工确认「方案 A」已给出（2026-10-13）
- 无需: Docker、真实 MySQL、真实 Redis
- 相关遗留: **#32**（本 Change 的 C2 正是它的闭环对象）