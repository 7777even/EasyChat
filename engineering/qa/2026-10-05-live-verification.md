# QA — 活体验证轮次（CI 首次实跑 + 端到端冒烟 + 活库核查）

- 日期: 2026-10-05
- 效率等级: **L0**（不改代码，只做验证并回填此前各处登记的「未执行」缺口）
- 范围: 不改任何生产代码。关闭散落在多份 QA 里的「沙箱无网络 / 未执行 / 未验」条目

## 为什么有这份文档

此前多份 QA 把「GitHub Actions 未实跑」「端到端未验」「活库核查未执行」列为**未运行项**。
本轮具备网络与活库，这些缺口已**实际执行**，故单列一份记录并回填原处。

## ① GitHub Actions 首次实跑 —— 通过

- Run: [#1](https://github.com/7777even/EasyChat/actions/runs/37280017414)，SHA `2884cbc`
- `conclusion: success`，耗时 58 秒，**4 个 job 全绿**

| Job | 关键步骤 | 结果 |
|-----|----------|------|
| 后端编译与单测 | `mvn test` + `mvn package` + 产物校验 | ✅ 349 例 / JDK 17 |
| 契约与规范门禁 | **19 个 gate step**（含新增「群聊提及判定纯核心」） | ✅ 全绿 |
| 前端构建 | `npm ci` + `npm run lint` + 组件测试 | ✅ 58 例 |
| **基线与活库表结构对账** | MySQL 8 容器 + **导入最新基线（模拟全新部署）** + 漂移检测 | ✅ |

**第四个 job 的价值最高**：`easychat.sql` 能在 **MySQL 8** 上干净导入 ——
本机开发库是 **5.7**，这条路径**从未验证过**。至此 `verify_schema_drift` 的
「本机 5.7 vs compose 8」长期不一致问题得到实证覆盖。

## ② 端到端冒烟 —— 96/96 通过

前置：后端真起（`mvn spring-boot:run`，dev profile），日志实证
`Flyway Community Edition 7.15.0` → `Successfully validated 12 migrations`（零改名识别生效）
→ Tomcat 5050 → Netty 5051。MySQL 5.7.39 + Redis 7 均在位。

| 脚本 | 结果 | 覆盖本轮改动 |
|------|------|--------------|
| `smoke_privacy.py` | **51/51** | `IdListTools.parse` 名单读写往返、非法 `visibility` → `CODE_1001`、非好友入名单被拒、在线状态 0/1 往返 |
| `smoke_audit_and_at_all.py` | **16/16** | `@所有人` 服务端鉴权：普通成员 → `2305`、**被拒消息未落库（落库数 0 → 0）**；4 条负向（普通群消息不误伤 / 单聊带 `atAll` 不拦 / 非布尔不触发 / 非法 `extraData` 不打断） |
| `smoke_password_session.py` | **29/29** | `isBCrypt` 改密 / 找回密码 / 会话失效全链路：改密后旧 token `2001`、改密失败不吊销会话、验证码不可二次使用 |

### 一个需要核实而非默认的点

`test@qq.com` 的口令哈希串在冒烟前后**不同**
（`$2a$10$4MCn3a…` → `$2a$10$FQWWh7…`），而脚本报告「密码已还原为原口令」。

核实结论：脚本用 `login(A_EMAIL, PWD_RAW)` **真实登录**校验还原，
而 **BCrypt 随机盐**本就使同明文两次编码得不同哈希。**脚本判定准确，不是残留脏数据。**

MD5 存量账号 `3289228667@qq.com` 全程未被触碰（仍为 32 位十六进制），
即 `isBCrypt` 的「存量零影响」论证得到活体佐证。

## ③ 活库核查（#13 的 T0.2）—— 存量零脏数据

此前只有「空串不可能等于真实 userId」的**论证**，本轮**实际查询**。

名单列共 5 处（`moment.visible_list` / `moment.invisible_list` +
`user_info.moment_visible_list` / `moment_invisible_list`）：

| 检查项 | 值 |
|--------|-----|
| `moment.visible_list` / `invisible_list` 非空行 | 0 / 0 |
| `user_info.moment_visible_list` / `moment_invisible_list` 非空行 | 1 / 1 |
| ★A 含空串元素（引号内仅空白） | **0** |
| ★B 含空元素（`[,` / `,,` / `,]` / `[]`） | **0** |
| ★C 引号未闭合 | **0** |
| ★D 元素带首尾空白 | **0** |

**结论：`IdListTools.parse` 修复对存量数据影响为零** —— 不仅命中结果不变，
实际连集合大小都不变。ADR-002「不需要数据迁移」由论证升级为**实证**。

## ④ 活库顺带查出：字面量 `"NULL"` 脏值（已确认无功能影响）

`test@qq.com.moment_visible_list` 的内容是**字面量字符串 `NULL`**
（`LENGTH()=4`，且 `IFNULL` 未替换 `<NULL>` 占位 → 不是 SQL NULL）。

`StringTools.isEmpty` **大小写敏感**，只把小写 `"null"` 当空：

```
isEmpty("null") = true      isEmpty("NULL") = false     isEmpty("Null") = false
parse("NULL")   -> ["NULL"]    ← 幽灵成员进入名单
parse("[null]") -> []           小写才被跳过
validate("NULL") -> CODE_1001   写入侧拒绝
```

### 为何判定「无功能影响」（两重证据）

1. **代码路径**：`canView` 的判定是 `visible.contains(viewerId)` /
   `!invisible.contains(viewerId)`，**不是**「名单非空则放行」。
   故 `["NULL"]` 与 `[]` 对任何真实用户**行为完全一致**。
   我最初判断「名单非空但只含垃圾 → 所有好友被拒」，**读 `canView` 后确认该判断错误**。
2. **不可达性**：`requireFriendSubset:629` 拒绝空名单，
   故任何一次成功保存 `visibility=3/4` 都会写入真实 JSON 数组并**覆盖**脏值；
   而 `visibility` 为 0/1/2 时名单列**根本不被读取**。

### 为何不修

- `StringTools.isEmpty` 有 **85 个调用点 / 35 个文件**，为其改大小写语义风险远大于收益；
- 仅在 `IdListTools.parse` 内跳过 `"null"` 属防御性加固，**收益接近零**
  （无任何可观测行为变化），却要为此开 L3 并改动已归档 Change 的解析契约；
- 脏值来自**历史/手工数据** —— 已确认当前代码与冒烟脚本**都无法再产生**它
  （`IdListTools.serialize` 只返回 Java `null` 或 JSON 数组；DDL 默认是 SQL `NULL`）。

→ **决定：仅登记为数据卫生项，不改代码。**

## 仍未执行项

| 项 | 原因 |
|---|---|
| **compose `migrate` 容器实跑** | `registry-1.docker.io` 返回 `EOF`，本机无 `mysql:8.0` / `node:20-alpine` 镜像。Docker 代理已配但不可达。**环境限制，非代码问题**。已在 `2026-10-04-flyway-migration-automation` 的 QA 保留该缺口 |
| UI 截图 | 需 GUI 环境。已按 `easychat-front/AGENTS.md` §4.1 走 B 形式（行为证据） |
| WebRTC 通话媒体链路 | 需两个真实客户端 |

## 结论

**通过。** 本轮不改代码，但把此前 3 类「未执行」缺口中的 **2 类实际补齐**（CI、端到端 + 活库），
并新增 1 个已定性的数据卫生项。
