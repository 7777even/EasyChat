# QA — BCrypt 哈希变体识别与 `matches()` 对齐

- 日期: 2026-10-04
- 效率等级: **L4**（命中 AGENTS §8「权限与认证」）
- Change: `openspec/archive/2026-10-04-bcrypt-variant-detection`
- 范围:
  - `utils/PasswordEncoder.java` —— 仅 `isBCrypt(String)` 方法体 + 两个新增私有常量
  - `service/impl/UserInfoServiceImpl.java` —— **未改动**，但 `login:266/288`、`updatePassword:403/413` 四处调用点的行为结论随之改变
  - `AGENTS.md` §6.2-2 —— 订正「`$2a$` 开头」的错误表述
  - 无接口 / 无表结构 / 无迁移脚本 / 无配置项变更

## 验收口径

| # | 口径 | 依据 |
|---|------|------|
| 1 | `isBCrypt` 覆盖 BCrypt 全部已知变体前缀 | spec-delta「BCrypt 格式判定与校验口径一致」 |
| 2 | `isBCrypt(p)==false ⟹ matches(any,p) 不可能成功` | design.md §1 不变式 I |
| 3 | 判定**不得**放宽到 MD5 / `$1$` / `$2$` / `$20$`~`$29$` | spec-delta「判定不得放宽到非 BCrypt 数据」 |
| 4 | 前缀形似但长度 ≠ 60 的截断串判 false | spec-delta「截断串不得被误判为 BCrypt」（ADR-004，红阶段追加） |
| 5 | `encode` / `matches` 既有语义零回归 | DoD「既有语义不回归」 |
| 6 | 存量数据零影响（现网全 `$2a$`，新旧判定同） | proposal「Impact」 |
| 7 | 测试有判别力（非恒绿） | AGENTS §2.1 第 1 条 |
| 8 | 密码交接门禁不受影响 | AGENTS §10 `verify_password_handoff.mjs` |

## 实际执行命令与结果

| 命令 | 结果 |
|------|------|
| `mvn -B -f easychat-java/pom.xml test`（**基线**，改动前） | `Tests run: 270, Failures: 0, Errors: 0` / `BUILD SUCCESS` |
| `mvn test -Dtest=PasswordEncoderTest`（**红阶段**，实现未改动） | `exit=1`，**3 个用例转红**，红因见下 |
| `mvn test -Dtest=PasswordEncoderTest`（实现后） | `Tests run: 11, Failures: 0` / `BUILD SUCCESS` / `exit=0` |
| 变异脚本（5 用例） | **`5/5 捕获`，漏网 0，无效 0** / `exit=0` |
| `mvn -B -f easychat-java/pom.xml test`（**全量回归**） | `Tests run: 281, Failures: 0, Errors: 0` / `BUILD SUCCESS` / `exit=0`（基线 270 + 新增 11） |
| `node scripts/verify/verify_password_handoff.mjs` | `结论：21/21 通过` / `exit=0` |
| `node scripts/check-openspec-hygiene.mjs` | `错误 0 / 警告 0` / `exit=0` |

### 红阶段明细（T1.6 —— 红因必须指向 `isBCrypt` 断言，而非编译失败或桩异常）

```
[ERROR] PasswordEncoderTest.allKnownVariantsAccepted:179
        $2b$ 是 BCrypt 已知变体，isBCrypt 必须放行 —— 漏放会导致该哈希走 MD5 分支
        并被二次加密（不可逆损坏） ==> expected: <true> but was: <false>

[ERROR] PasswordEncoderTest.isBcryptMustNotBeStricterThanMatches:72
        matches 能校验 $2b$ 开头的哈希，isBCrypt 却判 false —— 格式判定比校验更严，
        会导致该哈希走 MD5 分支并被二次加密 ==> expected: <true> but was: <false>

[ERROR] PasswordEncoderTest.nonBcryptShapesRejected:125        ← ★ 预判之外
        不应被判定为 BCrypt：$2a$ ==> expected: <false> but was: <true>
```

**第三个反例是本次最有价值的产出**：它推翻了 proposal 阶段的判断
（详见「结论 · 遗留与建议」）。

### 变异明细（证明用例非恒绿）

```
[基线] ✓ 全绿，后续「捕获」可归因于变异本身
[捕获] 退回原缺陷：只认 $2a$ 单前缀、无长度校验
[捕获] 放宽过头：前缀退化为 startsWith("$2")、且无定长校验
[捕获] 漏放 $2b$（变体集合退化为 a/x/y）
[捕获] 去掉定长校验（截断串被误判为 BCrypt）
[捕获] ★ 拒绝分支方向反转（把 if(missing) 写成 if(!missing) 那类错误）
=== 结论：5/5 个变异被测试捕获，漏网 0，无效 0 ===
✓ 测试有判别力
```

★ 那条**限定了遗留 #11 的适用范围**：静态字面量断言测不出条件分支方向，
但**行为测试能测出**。`docs/system-facts.md` #11 已据此收敛表述。

## 关键证据：`spring-security-crypto 5.6.0` 前缀接受集（实测）

枚举探针跑通后已删除源文件与 `target` 中的残留 `.class`
（残留 `.class` 曾使基线虚高为 271，清理后真实基线 270）：

```
默认生成前缀        = $2a$10$
matches("$2b$") = true      isBCrypt("$2b$") = false     ← 矛盾
matches("$2y$") = true      isBCrypt("$2y$") = false     ← 矛盾
$2x$                          -> matches false           ← 注意：$2x$ 被 Spring 拒绝

Spring 接受的 minor 集合 = [aby]
$2$（无 minor）-> false      $2A$（大写）-> false      $1$ -> false
$2a$ 但长度不足  -> false
```

## 未运行项

| 项 | 原因 |
|---|---|
| 带活库的登录 / 改密冒烟 | 需 MySQL + Redis + 真实账号；本次**无行为变化**（存量全 `$2a$`，新旧实现判定逐字节同），冒烟无法证伪「无影响」这一结论，只会对同一份未变数据重复登录 |
| `mvn package` / 活体启动 | 变更不涉及打包与依赖，无需重复 §2 矩阵的上一行 |
| CI 实跑 | 沙箱无网络与远端；`mvn test` 已本地全量实跑 |

**明确声明**：本次**未**证明「库中出现 `$2b$` 哈希时登录恢复正常」这一**运行时**行为，
只证明了**判定函数**对该输入返回 `true`。前者需构造活库数据，属活体验证范畴，已如实登记。

## 结论

**达成**。8 条验收口径逐条满足，全量回归无新增失败（281/281）。

### 遗留与建议

1. **proposal 阶段有一处判断被实证推翻**（已在 ADR-004 更正并留档）：
   当时认定「长度校验不增安全性、只增加出错面」，实测不成立——
   BCrypt 格式**长度恒为 60**，是格式定义而非启发式阈值。
   该追加已**单独请求 L4 人工确认**后纳入，未静默扩大范围。
2. **规范即成因**：`AGENTS.md` §6.2-2 曾把「`$2a$` 开头」写进规范，
   实现很可能**据此**写成单一前缀匹配。规范把实现细节当契约，
   会主动诱导实现过窄。已订正为「长度为 60 的 BCrypt modular-crypt 格式」。
3. **建议**：`StringTools.encodeByMD5` 与 MD5 双验证分支在存量账号清零后可移除，
   届时本变更引入的白名单可进一步收敛。当前保留。
