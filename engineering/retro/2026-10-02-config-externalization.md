# Retro — 运行时配置外置

- Change: `openspec/changes/2026-10-02-config-externalization/`
- 日期: 2026-10-02

## 一、做得好

1. **门禁先红后绿**。先写 `verify_no_hardcoded_secret.mjs` 并在**未改造**的配置上跑到 2/15 exit=1，再实施 → 19/19。避免"改完再补断言"的自证清白，且这个门禁永久留在 CI 里防回归。
2. **ADR-002 救了零配置体验**。差点把 DB 密码写成 `${SPRING_DATASOURCE_PASSWORD}`（无默认值），那会让任何没设该变量的环境启动即崩、把"缺配置"变成"启动失败"。保留 dev 默认值 + prod 覆盖为空，dev 环境行为与变更前**逐字节一致**。
3. **QA 用三组 profile 场景证伪**。特别是 prod 未注入时抓到 `using password: NO` 这一行——它比"启动失败"四个字有力得多，直接证明了"没有烘焙凭据"。只跑一次"能启动"是自欺欺人。
4. **顺手挖出两个真问题**：`easychat.project-folder` 是死键（全仓无人读 `EasyChatProperties#getProjectFolder`，落盘实际由 `project.folder` 提供）；`spring-boot-maven-plugin` 锁 2.2.6 与 parent 2.6.1 倒挂。都已回写 system-facts 并入批次 5 清单。

## 二、问题

1. **门禁对 `spring.datasource.username=root` 报了红**。DB 用户名算不算敏感？脚本按 `/password|secret|credential|token|username$/` 匹配，判过严。实施时把它一并改成占位符消了警，但这条规则的**边界没在 design 里讲清**，下次改配置的人可能被误报困扰。
2. **`.env` 的实际注入路径有认知陷阱**。Spring Boot 2.6 不读 `.env`（3.0+ 才有 `spring.config.import`），ADR-003 明确"不引新依赖"，于是 `.env` 只是个"给 compose / shell 用的载体"。这个约束**只写在 README 和 ADR 里**，没有任何门禁或启动期校验能提醒人忘掉 `--env-file`——忘一次就是"连的是本地库还以为在跑生产"。
3. **`CallService.rooms` 内存单例 = 后端不能多实例**。这次配了 Dockerfile + compose，就在 compose 隔壁发现：容器一旦 scale >1，通话房间路由立刻失效（`USER_CONTEXT_MAP` 同理，但广播走 RTopic 所以侥幸无碍）。**加了容器化反而更接近"可以多开"的错觉**。
4. **既有日志级别误用没人管**：`InitRun` 用 `logger.error` 打"服务启动成功"；控制台还是 GBK 解码 UTF-8 全是乱码。排查时第一眼以为启动失败，浪费了一轮。属变更前既有问题，但**它就在这次验证路径上**。

## 三、原因

1. 门禁规则写的时候没先定"敏感"的定义边界，凭直觉写了正则。
2. 架构上「不引新依赖」（AGENTS §8 L4 守则）是对的，但把"配置怎么真的进到进程里"的责任留给了人的记忆，没有机制兜底。
3. 容器化只解决了"能不能跑起来"，没触发"多实例下什么会坏"的审视——`docker compose up --scale backend=2` 之前没人想过这个约束。
4. 该项目历史遗留的日志规范从未被门禁覆盖（门禁只覆盖了契约/IPC/规格/Mapper/密码五条线，日志级别不在其中）。

## 四、改进方案

| 方案 | 落点 | 优先级 |
|------|------|--------|
| 门禁补"DB 用户名/库名属非敏感"的显式白名单，并在脚本头部注释写清敏感定义 | `verify_no_hardcoded_secret.mjs` | 立即（本 Change 内补） |
| `docker-compose.yml` 的 backend service 加 `SPRING_PROFILES_ACTIVE: prod` 默认值，使"忘加 --env-file"也不至于静默用 dev；README 顶部加一行醒目提示 | 批次 1 骨架文件微调 | 立即（本 Change 内补） |
| **后端单实例约束显式化**：在 compose 注释 + README §7 写明"backend 不可 `--scale`"，并在 `CallService` 类注释里加一句多实例限制说明 | 文档 + 注释 | 本 Change 内 |
| `CallService.rooms` / `USER_CONTEXT_MAP` 迁 Redis（多实例安全的房间注册表） | 批次 5 或独立 Change（L4，WS 协议） | 中 |
| 日志级别与控制台编码规范纳入门禁（如禁止 `logger.error` 打成功信息、logback 强制 UTF-8 + `System.out` 同编码） | 独立 Change（L2） | 中 |
| `verify_schema_drift.mjs`（AGENTS §6.4 早已建议但未实现） | 批次 5 | 中 |
| `easychat.project-folder` 死键与 `EasyChatProperties` 冗余 Java 默认值清理 | 批次 5（L2 代码清理） | 低 |

## 五、给下一批的经验

- 配门禁脚本时，**先定义"什么算违规"再写断言**，否则会被自己的正则误报。
- 容器化交付要连带回答一个问题：**这套东西能不能多开？** 不能就要写进文档和类注释，别让 scale 变成地雷。
- 验证"配置生效"不能只跑一次成功路径，**必须构造一条应该失败的路径**（prod 无注入 → `using password: NO`），那才是真的证据。
