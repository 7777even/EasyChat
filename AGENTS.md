# AGENTS.md — AI 编码协作入口

面向 AI 助手 / 自动化编码的项目级约束入口。**动手前先按 §1 判定改动等级（L0–L4）；L2 及以上在生成或修改接口、安全、持久化代码前，必须读完本文 §3 与 §6，再动手。**

本项目是 **EasyChat** 实时聊天系统（Electron 桌面端 + Spring Boot 后端 + Vue 3 前端），与参考项目 `mm-security-platform` 共享同一套 AI 规范骨架（分级 / 契约 / 记录闭环）。

## 0. 仓库结构

```
EasyChat/
├─ AGENTS.md                      ← 本文件（AI 协作入口）
├─ easychat-java/                  ← Spring Boot 后端服务
│  ├─ src/main/java/com/easychat/
│  │  ├─ controller/               ← HTTP 协议、DTO接收、鉴权入口、响应封装
│  │  ├─ service/                  ← 业务编排、事务边界
│  │  ├─ mapper/                   ← 数据库访问
│  │  ├─ entity/po/                ← 持久化对象（与表结构对应）
│  │  ├─ entity/dto/               ← 接口入参
│  │  ├─ entity/vo/                ← 接口出参
│  │  ├─ entity/query/             ← 分页查询条件
│  │  ├─ entity/enums/             ← 枚举定义
│  │  ├─ config/                   ← 配置类
│  │  ├─ websocket/                ← WebSocket 处理
│  │  ├─ redis/                    ← Redis 组件
│  │  ├─ exception/                ← 业务异常
│  │  ├─ aspect/                   ← AOP 切面
│  │  ├─ annotation/               ← 自定义注解
│  │  └─ utils/                    ← 工具类
│  └─ src/main/resources/
│     ├─ application.properties
│     └─ com/easychat/mappers/     ← MyBatis XML
├─ easychat-front/                 ← Electron + Vue 3 桌面客户端
│  ├─ src/main/                    ← Electron 主进程
│  ├─ src/preload/                 ← 预加载脚本
│  └─ src/renderer/src/            ← Vue 3 渲染进程
└─ easychat.sql                    ← 数据库初始化脚本
```

## 1. 分级工作流（L0–L4 决策树）

动手前先判定等级，并在回复中用一句话说明判定与理由。**分级只决定流程重量，不豁免 §3、§4、§6。**

- **不改代码**（解释 / 评审 / 状态汇报 / 只读检查 / 文本润色）→ **L0**：直接完成；不建文件、不起子 Agent。
- **改代码但不改业务能力 / 接口契约 / 权限语义，且 L1 四门槛全满足** → **L1**：说明范围 → 直接改 → 跑最小验证 → 输出结果。
- **改代码但属依赖 / 构建 / 脚手架 / lint / 配置 / 非业务技术债，或 L1 门槛缺一** → **L2**：说明方案与影响 → 执行 → 跑受影响目标验证。
- **改业务能力**（接口能力 / 业务规则 / 状态流转 / 权限语义 / 数据模型）→ **L3**：提案 → 人工确认 → 实施 → 验收。
- **高风险**（认证鉴权 / 数据库结构 / WebSocket 协议 / 部署配置基线）→ **L4**：按 L3 执行，且实施前取得人工确认。

### 1.1 L1 四条门槛（缺一即升 L2 / L3）

① 不新增或改变业务能力、接口契约、权限语义、数据库结构；② 改动不超过 3 个文件；③ 目标明确、可逆，验证可在 5 分钟内完成；④ 不新增生产依赖。

### 1.2 规则优先级仲裁

1. 平台安全策略与人工当场指令
2. 本文档（含 §3 接口契约、§6 红线）
3. 已确认的设计文档与任务清单
4. Skill / 插件自带的工作方法

## 2. 最小验证矩阵

| 改动范围 | 必跑验证 |
|---------|---------|
| 文档、规范、注释 | 检查格式与一致性 |
| 单个 Controller / Service / DTO 的局部修改 | `mvn compile` |
| Entity / Mapper / SQL / 分页 | `mvn compile` + 启动验证 |
| pom.xml、依赖、构建配置 | `mvn package -DskipTests` |
| 对外接口增删改 | 接口联冒烟 |
| L3 / L4 | 按验收标准全量 |

## 3. 接口契约规则

1. **统一响应包络**：所有业务响应统一 `Result<T>`（`code` / `message` / `data`），`code=0` 为成功；非 0 由全局异常处理器统一转换。**禁止** Controller 自定义第二套响应外壳或裸返实体。
2. **HTTP Status 语义**：成功 200，参数错误 400，未认证 401，无权限 403，资源不存在 404，冲突 409，业务错误对应 4xx，系统错误 500。**禁止**「一律 HTTP 200 + 仅靠 body code 区分成败」。
3. **Controller 职责**：路由、参数绑定、`@Valid`、调用 Service。**不写业务逻辑**；**不直接注入 Mapper**；**不自定义响应外壳**。
4. **Service 职责**：业务规则、事务边界。**不感知 HttpServletRequest**；**不吞异常**。
5. **Mapper 职责**：数据访问，**不写业务判断**。
6. **DTO 职责**：出入参对象，**每个字段必须有中文注释 / `@Schema`；不复用 Entity 做出参**。
7. **Entity 职责**：与表结构 1:1 的持久化对象，**改动必须同步 `easychat.sql`**。

### 3.1 错误码分段

| 段 | 域 | 现有码 |
|----|-----|-------|
| `0` | 成功 | `0` |
| `1000-1999` | 通用（参数、系统） | `1001` 参数非法、`1002` 系统错误 |
| `2000-2099` | 鉴权域 | `2001` TOKEN_EXPIRED、`2002` TOKEN_INVALID |
| `2100-2199` | 用户域 | `2101` 用户不存在、`2102` 用户已存在、`2103` 密码错误 |
| `2200-2299` | 聊天域 | `2201` 消息不存在、`2202` 无权限操作消息 |
| `2300-2399` | 群组域 | `2301` 群组不存在、`2302` 不在群组中 |
| `2400-2499` | 好友域 | `2401` 非好友关系、`2402` 好友申请已存在 |
| `2500-2599` | 朋友圈域 | `2501` 动态不存在 |
| `2600-2699` | 文件域 | `2601` 文件不存在、`2602` 文件上传失败 |

## 4. 分页请求规范

### 4.1 PageRequest（统一分页包装）

```java
@Data
public class PageRequest<T> {
    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private Integer pageNum;
    private Integer pageSize;

    @Valid
    private T query;

    public int getPageNum() { return pageNum != null ? pageNum : DEFAULT_PAGE_NUM; }
    public int getPageSize() { return pageSize != null ? pageSize : DEFAULT_PAGE_SIZE; }
}
```

### 4.2 PageResult（统一出参）

```java
public class PageResult<T> {
    private List<T> list;
    private Long total;
}
```

分页查询接口出参统一为 `Result<PageResult<T>>`。

## 5. HTTP Method 规范

- **GET 请求**：仅用于幂等查询操作
- **POST 请求**：用于所有写操作和复杂查询
- **禁止使用** `@RequestMapping` 不指定 method（当前遗留逐步替换为 `@GetMapping` / `@PostMapping`）
- 接口前缀统一 `/api/`
- WebSocket 端口独立配置（当前 `ws.port=5051`）

## 6. 既有项目约束

### 6.1 技术栈

Spring Boot + MySQL + Redis + Netty（WebSocket）+ MyBatis（XML 映射）。

- 端口：HTTP `5050`，WS `5051`，context-path `/api`
- 鉴权：自定义 Token + Redis 会话（`@GlobalInterceptor` 拦截器）
- WebSocket：Netty 实现，心跳 + 消息分发
- 前端：Electron + Vue 3 + Element Plus + Vite

### 6.2 安全红线

1. **会话管理**：Token 与会话信息存储在 Redis，键前缀 `constants.Constants.REDIS_KEY_WS_TOKEN`。
2. **密码安全**：MD5 加密存储（后续建议升级为 BCrypt）。
3. **SQL 注入**：禁止字符串拼接 SQL，MyBatis 使用 `#{}` 参数绑定。
4. **操作权限**：用户只能操作自己的资源（消息、好友等），接口内必须校验当前用户身份与资源归属。
5. **文件上传**：限制文件类型与大小，禁止可执行文件上传。

### 6.3 工程约定（Git / 提交）

- **提交格式 `type(scope): 描述`**，scope 固定枚举：
  `auth`（认证）、`user`（用户）、`chat`（聊天）、`group`（群组）、`contact`（好友）、`moment`（朋友圈）、`file`（文件上传）、`ws`（WebSocket）、`admin`（管理后台）、`common`（公共组件/工具）、`config`（配置）、`docs`（文档）、`chore`（杂项）
- 提交信息**只写一行标题**，禁止正文分点列表
- 跨域改动**按影响面拆成多个提交**

### 6.4 数据库规则

1. 表结构变更必须同步更新 `easychat.sql`
2. 字段变更需评估存量数据影响
3. 新建表必须包含 `create_time` 字段
4. 逻辑删除优先使用 `status` 字段标记而非物理删除

## 7. 前端规范

### 7.1 请求层

- 统一使用 `src/renderer/src/utils/request.js` 封装
- `baseURL` 由环境变量控制（生产/开发）
- 响应拦截器统一处理：成功（code=0）、登录过期（code=2001）、其他错误
- 禁止在组件中直接使用 `axios`，必须通过 `request` 封装

### 7.2 组件规范

- 使用 Vue 3 `<script setup>` 语法
- 组件命名 PascalCase
- 页面风格统一使用 Element Plus 组件
- 文件名 kebab-case（样式/资源）/ PascalCase（组件）

### 7.3 状态管理

- Pinia stores 位于 `src/renderer/src/stores/`
- 简单组件内状态使用 `ref` / `reactive`
- 跨页面共享状态使用 Store

## 7. 工程记录闭环

实施任务的唯一真源是 `openspec/changes/<name>/tasks.md`。

- 会话内进度跟踪只作临时备忘，不写入仓库；任务状态只回填 `tasks.md` 勾选框。
- 禁止在 `openspec/` 之外建立第二套需求规格或任务清单（含 skill 生成的计划文件、持久化待办）。

三类目录职责不重叠：

| 目录           | 回答的问题           | 特征                                 |
| -------------- | -------------------- | ------------------------------------ |
| `docs/`        | 系统**现在**是什么样 | 长期共识，跨版本有效，改了要同步代码 |
| `openspec/`    | 系统**将要**怎么变   | 唯一业务规格来源                     |
| `engineering/` | 这次**做得怎么样**   | 短期过程记录：计划、QA、复盘         |

- 短期开发记录放 `engineering/`，**不放 `docs/`**；QA 结果、发布检查、复盘同理。

### 7.1 L3 / L4 强制 OpenSpec 四件套

L3 / L4 改动动手前必须完成并闭环以下四件套（位于 `openspec/changes/<name>/`），且经末尾「人工确认关卡」确认后才允许写代码：

- `proposal.md`（Why / What / Capabilities / Impact + 人工确认关卡）
- `design.md`（架构、决策 ADR、风险、依赖、数据影响）
- `tasks.md`（≤2h 可勾选任务，[TDD] 先写失败测试；任务状态只回填此处）
- `spec-delta.md`（新增 / 修改 / 移除 三段，与 `spec.md` 同构）

四者须闭环：`proposal` 的 Capabilities ↔ `spec-delta` 的 Requirement ↔ `tasks` 的验收标准一一对应。禁止 L1 / L2 建立 OpenSpec Change。

**归档闭环（全勾必归档）**：`tasks.md` 全部勾选后，必须在**同一次交付内**完成收尾，不允许滞留 `changes/`：

1. **spec 回填**：将 `spec-delta.md` 合入 `openspec/specs/<capability>/spec.md`（新建或扩充 capability，Requirement/Scenario 格式）。
2. **归档**：`git mv openspec/changes/<name> openspec/archive/<YYYY-MM-DD>-<name>`（日期前缀必带）。
3. **命名与元数据**：进行中 Change 建议带 `YYYY-MM-DD-` 前缀，且每个 Change 含 `.openspec.yaml`（`schema: spec-driven` + `created: <YYYY-MM-DD>`）。

### 7.2 QA / Retro 即刻记录

L3 / L4 任务完成后**即刻**写 `engineering/qa/` 与 `engineering/retro/`，不允许攒到最后补；L0–L2 不写。

- QA：范围、验收口径、实际执行命令与用例数、未运行项、结论；**证据是结论必要附件**（接口用例附 curl / 冒烟终端输出快照，置于同目录引用文件名）。
- Retro：做得好 / 问题 / 原因 / 改进方案四段式。

### 7.3 模板体系

新建上述四件套与 QA/Retro 时，复制 `templates/` 目录对应模板填充，避免格式漂移：

| 模板                              | 用途                        |
| --------------------------------- | --------------------------- |
| `_openspec-proposal_template.md`  | 四件套 · proposal           |
| `_openspec-design_template.md`    | 四件套 · design             |
| `_openspec-tasks_template.md`     | 四件套 · tasks              |
| `_openspec-spec-delta_template.md`| 四件套 · spec-delta         |
| `_qa_template.md`                 | engineering/qa 记录         |
| `_retro_template.md`              | engineering/retro 记录      |

`templates/README.md` 为索引与用法说明。

### 7.4 完成标准（Definition of Done）

任一 L3 / L4 改动在声称完成前，必须满足：

1. **验收标准达成**：`openspec/changes/<name>/tasks.md` 全部勾选，验收标准逐条满足。
2. **回归全绿**：按 §2 矩阵对应行执行，`mvn compile` / `mvn package -DskipTests` 0 error、接口链路冒烟通过。
3. **文档同步**：代码改动若改变契约 / 行为 / 数据结构，同步更新 `easychat.sql`、`docs/` 与前端请求调用方；禁止把短期记录写进 `docs/`。
4. **归档闭环**：四件套中的 spec-delta 已回写 `specs/`，Change 已归档至 `archive/`（§7.1）。

## 8. L4 硬门禁清单

以下任一类改动命中即升 **L4**（按 §1 取得人工确认后才实施），不得按 L1 / L2 直接动手：

- **契约语义**：`Result<T>` 包络结构、`ResponseCodeEnum` 错误码分段与语义、`/api/` 前缀变更。
- **权限与认证**：`@GlobalInterceptor` 拦截器语义、Token 会话机制、鉴权白名单。
- **数据库结构**：表 / 字段 / 索引 / 约束变更、逻辑删除与状态字段语义。
- **WebSocket 协议**：Netty 处理器链、心跳协议、消息分发包络格式。
- **横切配置**：`application.properties` 生产基线、`CorsConfig` 配置。
- **依赖与框架**：生产依赖新增、Spring Boot / MyBatis / Netty 版本升级。

## 9. Review 结论三选一

任何代码评审 / 变更评审的结论必须为以下三者之一，**禁止含糊带过**：

- **通过**：无需修改，可直接合入。
- **需修改**：明确指出修改点，修改后无需再全员评审。
- **需人工决策**：存在高风险 / 规范冲突 / 范围扩散等需拍板事项，转交人工确认（对应 L4 关卡）。

> 不得出现「基本可以」「再看下」「问题不大」等模糊结论。L3 / L4 另需**双轴 Review**：①规格符合性（是否严格实现已确认 Task、契约与验收标准）；②代码质量（越界改动、错误边界、测试遗漏、无关重构、无用依赖）。
