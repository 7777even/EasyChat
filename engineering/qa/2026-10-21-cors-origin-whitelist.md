# QA — CORS 允许来源可配置 + 宽松组合显式告警（L4）

- 日期: 2026-10-21
- 效率等级: **L4**（命中 `AGENTS.md` §8「横切配置：`application.properties` 生产基线、`CorsConfig` 配置」；
  实施前已取得人工确认：用户选定「按推荐执行 → **默认不变 + 变显性 + 可收紧**」）
- 范围: `config/CorsConfig`、`application.properties`、`CorsConfigTest`
- 配套: `openspec/archive/2026-10-21-cors-origin-whitelist/`（四件套 + spec 回写）
- 风险背景: `docs/system-facts.md` §14 #30

## 验收口径

1. **默认行为一字未改**：既有 4 例（回显任意来源 / 预检放行任意方法与头 / 最高优先级 / 无 Origin 不加头）**全程保持绿**；
2. 允许来源**可配置**，且**白名单外的来源不被回显**（否则「收紧」只是摆设）；
3. 「凭据 + 通配来源」并存时**启动即告警**，且告警含**风险条件**与**收紧开关**；
4. 空配置**回退**到 `*` 并记 ERROR（误配不应把应用打死），且**不得静默**。

## 实际执行与结果

| 项 | 命令 | 结果 |
|---|---|---|
| TDD 红阶段 | `mvn -Dtest=CorsConfigTest test` | **8 例中 4 条转红**（正是新增的 4 条），**既有 4 条保持绿** ⇒ 证明默认行为未被改动 |
| TDD 绿阶段 | 同上 | **8/8 全绿** |
| 后端全量 | `mvn test` | **EXIT=0**（0 失败；基线 978 → **982**，新增 4） |
| 本地门禁 | pre-push 16 道批量 | 失败 **0** |
| 表格列数 | `tabcheck.mjs` | README / system-facts / QA / scripts 各表 **0 错位** |
| 打包 | `mvn -DskipTests package` | **EXIT=0**（51.7MB jar） |

### 新增的 4 条用例（各带反向制衡）

| 用例 | 断言 | 制衡点 |
|---|---|---|
| `allowedOriginsAreConfigurable` | 白名单内回显、白名单外**不回显** | 若实现仍用 `*`，白名单外那条**必红** |
| `wildcardDetectionIsPureLogic` | 含 `*`（含前缀通配 `http://*`）即宽松；精确白名单不宽松 | 防「只判 `== "*"`」漏判前缀通配 |
| `permissiveCombinationProducesActionableWarning` | 告警非空、含配置键、含风险条件；精确白名单**不告警** | 防「只喊一声『有风险』」的噪音告警 |
| `emptyConfigFallsBackToWildcard` | 空/全逗号 → 回退 `*`，且**仍判宽松** | 防「回退后静默」——回退不得把风险藏起来 |

## 未运行项（如实登记）

1. **未实际收紧**（默认仍 `*`）—— 收紧需把桌面端渲染层来源纳入白名单，
   否则**桌面端立刻访问不到后端**；该项属**部署形态相关**，由运维按实际来源收紧。
2. **未做浏览器端端到端验证**（真实 Chromium 发跨域请求观察响应头）——
   本次用 `MockHttpServletRequest/Response` 在 Filter 层验证（与既有 4 例同一层级），
   浏览器差异（`Origin: null`、扩展页 origin）**未覆盖**。
3. **未验证 `allowOrigin` 与 `allowOriginPattern` 的交互**（本实现只用 pattern；
   Spring 在同时设置两者时以 `allowOrigin` 为准，本次未触及）。

## 证据

- 红阶段输出：`expected: <null> but was: <不在白名单的来源不得被回显…>`、
  `expected: <true> but was: <false>`（通配判定）、`expected: not <null>`（告警为空）—— **4 条如期转红**。
- 绿阶段：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。
- 既有 4 例全程保持绿 —— 这是「默认行为未变」的**直接证据**（红阶段它们就已经是绿的）。
- 提交：`fix(config)` + `docs(config)`（见变更日志）。

## 结论

**通过**。安全暴露面**未扩大**（默认逐字未变），但那颗「哑弹」从隐性变显性，
且收紧路径从「将来记得做」变成「一个配置项」。遗留：默认未收紧（待部署形态确定后由运维执行）。
