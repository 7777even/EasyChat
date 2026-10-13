# Retro — 后端集成测试层（无 DB 方案 A）

- 日期: 2026-10-13
- 效率等级: **L3**（`openspec/changes/2026-10-13-backend-integration-test-layer`）

## 做得好

- **计划里把「可行性验证」放在写任何业务断言之前**（tasks 阶段一 + design 的 T4「不可行则停止」）。
  这不是形式主义：ADR-002 依赖「数据源不可达时上下文仍能起」这个**未验证前提**，
  而实测发现启动期有**三个**外部依赖（敏感词 `@PostConstruct`、Redisson 的 Bean 创建期建连、
  `MessageHandler` 的 topic 订阅），若先写业务断言，会在错��的前提上堆三小时才发现方案不可行。
- **让启动报错自己说话**：T1 故意不加任何 mock、且把数据源指向**不可达端口**而不是真库。
  于是「谁需要外部依赖」以失败原因的形式直接暴露，且全程零写入活库的风险。
  依据是 AGENTS §2.1 第 12 条（禁止用局部证据推断系统行为）—— 我确实提前读过
  `InitRun` / `SensitiveWordServiceImpl`，但**读代码得到的是「有三处 `@PostConstruct`」，
  报错给出的是「实际按此顺序失败、且 Redisson 非懒加载」**。
- **每条「拒绝」都配一条「放行」**（T6-②、T11）。若只断言非法被拒，把守卫写成「恒拒绝」照样全绿。
- **变异验证不是最后一步，而是每写完一个测试类就做**。这在 T8 抓到了我自己的**恒真断言**：
  若按计划留到 T14 才做，问题会留到归档前才暴露。
- **意外收获**：`@SpringBootTest` 会加载完整 `application.properties`，
  逼出 `@ActiveProfiles("test")` 的必要性 —— 而这正是「本地测试连活库」这类事故的常见入口。

## 问题

- **我写的断言连续三次形态不对**（都不是环境问题，是我测错了东西）：
  1. T8 用 `jsonPath("$.code")` 断言限流 → 响应体可能为空，取不到值；
  2. 改用「body 不含 `code:1001`」→ **失败且极具误导性**：那个 1001 其实来自业务（验证码已过期），
     限流与业务**共用同一个错误码**；
  3. 改用「body 不含中文文案」→ **恒真**（`getContentAsString()` 默认 ISO-8859-1 解码，
     UTF-8 中文变乱码），靠变异检验才发现。
- **MyBatis Mapper 按类型 mock 不生效**：`@MockBean SensitiveWordMapper` 看着对，实际 mock 未生效，
  `SensitiveWordServiceImpl#init` 仍在真连库。必须按 bean 名 `name = "sensitiveWordMapper"`。
- **我自己写的「防连活库」元断言反而会连库**：初版用
  `dataSource.getConnection().getMetaData().getURL()` 取 URL —— 取 URL 本身就要先连库，
  而这恰是数据源指向真库时最危险的动作。
- **漏 `@AutoConfigureMockMvc`**：上下文已能启动，却 `@Autowired MockMvc` 拿不到 bean。
- **测试日志噪音**：主工程的 `logback-spring.xml` 也在 test classpath 上且 root 取 `${log.root.level}`，
  一条 BeanCreationException 在日志里重复 **50 次以上**，真正的首行原因被淹没。
- **证据文件返工三次**：初版 42KB 全是重复 Bean 链；第二版丢了根因行（模式定义了却没加进 KEEP）；
  第三版标签按**记忆中的阶段顺序**命名，与文件真实内容不符 —— 引用错标签即误导。
- **顺带撞见两个既有缺陷**（`RedisConfig` catch 类型不匹配、`admin` 为 null 时 NPE），
  均在本 Change 范围外，只能登记。

## 原因

- **断言设计时想的是「接口应该返回什么」，而不是「我要证明的行为是什么」**。
  于是自然会去断言响应码 / 响应体 —— 而这两者都**混入了业务层的噪音**。
  正确的问法是：*这条用例要证明「守卫在方法体之前拦下了请求」吗？* 若是，判据就应是
  `verify(service, never())`，与业务层返回什么**完全无关**。
- **对 MockMvc 的默认行为做了假设**（字符集、按类型 mock bean、是否注册 MockMvc）。
  这些都属于「框架默认值」，而框架默认值恰恰是**不读文档就不知道、读了也会忘**的部分。
- **重复的 Bean 链**掩盖了「一行根因」，这与本仓反复出现的「静态断言只看得到字面量」是同一类问题：
  信息存在但不可读，读不到就等于不存在。
- **证据生成脚本自己也会有 bug**（定义了模式没用、标签靠记忆），
  与变异脚本「汇总行把漏网算成捕获」同源 —— **工具也需要被检验**。

## 改进方案

1. **写 HTTP 行为断言前先写一句「本用例要证明的行为是什么」**，再选判据。
   只要涉及守卫（拦截 / 校验 / 限流），**优先用 `verify(service, never()/times(1))`** 而非错误码，
   因为它天然不受业务层噪音影响，且「守卫被删」时立刻失败。
2. **凡断言中文文案，必须显式字符集**：`getContentAsString(StandardCharsets.UTF_8)`。
   这条要写进本仓的测试约定（本次已写进代码注释，**建议后续进 `easychat-java` 的测试规范**）。
3. **MyBatis Mapper 一律按 bean 名 mock**：`@MockBean(name = "xxxMapper")`。
   建议在 `IntegrationTestBase` 的类注释里固化（已写），并考虑扩到其它需要 mock Mapper 的测试基类。
4. **新增 `@SpringBootTest` 子类必须继承 `IntegrationTestBase`**，否则三个启动期 mock 极易漏写。
5. **测试日志一律配 `logback-test.xml`**（本仓已加），避免一条错误刷 50 行。
6. **证据/工具脚本本身要做一次「自检」**：标签按文件真实内容命名（不靠记忆）、
   定义的模式必须真的进 `KEEP`、生成后**人工读一遍**再入库。
7. **变异验证提前到每个测试类完成时**，不要留到归档前 —— 本次正是它抓到了恒真断言。
8. **「顺带撞见的既有缺陷」当场登记、但不动手**：本次两个缺陷都写进了 QA 结论与代码注释，
   留给独立变更，避免把「加测试」的 L3 悄悄扩大成「改生产逻辑」。