# scripts/ — 自动化门禁脚本

本目录存放对齐 `AGENTS.md` §10 的机控脚本。规范靠人遵守会有漂移；脚本则把关键纪律翻译成"不通过就失败"的硬闸门。

## 脚本清单

| 脚本 | 触发时机 | 守门内容 |
|------|----------|----------|
| `commit-msg-lint.mjs` | git hook `commit-msg` | 提交格式 `type(scope): 描述`、type / scope 枚举、描述含中文、禁止 body |
| `pre-commit-guard.mjs` | git hook `pre-commit` | 暂存区黑名单（构建产物、日志、临时文件）；QA 证据附件除外 |
| `check-api-contract.mjs` | 本地手动 / CI | 后端 Controller 路由 vs 前端 `Api.js` 调用，找出孤儿路由 / 潜在漂移 |
| `check-openspec-hygiene.mjs` | git hook `pre-push` | 进行中的 Change 是否四件套齐全、tasks.md 全勾但未归档阻断推送、archive 内 tasks.md 存在未勾选任务阻断推送 |
| `check-ipc-registration.mjs` | git hook `pre-push` / CI | `ipc.js` 导出与 `index.js` 调用不匹配（漏注册即静默失效） |
| `verify/verify_no_hardcoded_secret.mjs` | git hook `pre-push` / CI | 配置基线含裸凭据、prod profile 含公共 TURN 凭据或 DB 默认可用密码、`.env` 入库 |
| `verify/verify_mapper_params.mjs` | git hook `pre-push` / CI | Mapper XML 占位符与方法签名不匹配（写错运行期才抛 `BindingException`） |
| `verify/verify_ws_frame_parity.mjs` | git hook `pre-push` / CI | WS 帧号两端对账：漂移 / 重复 / 空洞 / 落库帧无 case / 新增帧未声明意图 / `KNOWN_GAP` 过期 |
| `verify/mutation_ws_frame_parity.cjs` | 手动（改帧协议后必跑） | 变异检验：故意破坏 9 处帧协议，验证上面的门禁**真的会失败** |
| `verify/mutation_channel_online_status.cjs` | 手动（改 WS 在线状态逻辑后必跑） | 变异检验：故意破坏 8 处 `ChannelContextUtils` 隐私逻辑，验证单测**真的会转红** |
| `setup-git-hooks.mjs` | 一键安装脚本 | 把上述脚本注册到 `.git/hooks/` |

## 安装

```bash
node scripts/setup-git-hooks.mjs
```

安装后：
- `git commit` → 自动触发 commit-msg-lint + pre-commit-guard
- `git push`  → 自动触发 check-openspec-hygiene（全勾未归档、或已归档但未全勾，均拒绝推送）

## 跳过（慎用）

紧急修复时如需临时跳过 hook：`git commit --no-verify`。
跳过即视为主动豁免，AI 不得自主建议 `--no-verify`。

## 纯脚本组合（不装 hook）

```bash
node scripts/commit-msg-lint.mjs /path/to/commit-msg-file
node scripts/pre-commit-guard.mjs
node scripts/check-api-contract.mjs [--strict]
node scripts/check-openspec-hygiene.mjs [--strict]
```

## --strict 模式

`check-api-contract.mjs --strict` 与 `check-openspec-hygiene.mjs --strict`：
将 WARN 级别也升级为 exit 1，用于 CI 门禁收紧。

## WS 帧协议对账（`verify_ws_frame_parity.mjs`）

WS 帧号是服务端 `MessageTypeEnum` ↔ 客户端 `wsClient.js` `case` 的**跨进程契约**。
两端错位后的表现是「不崩但功能静默失效」——这是最难靠人工回归发现的一类问题，
2026-10-02 本门禁首次运行即抓出位置消息（25）与语音消息（24）端到端未接通。

它自动从服务端源码解析**落库白名单**（`ChatMessageServiceImpl` 的 `ArraysUtil.contains(new Integer[]{...})`），
不需要人工维护第二份清单。每个帧必须显式声明意图：

| 表 | 含义 | 加入条件 |
|----|------|---------|
| `MUST_HANDLE` | 需客户端实时处理，缺 case 即阻断 | 推送帧（聊天/控制/信令） |
| `INTERNAL_FRAMES` | 服务端内部帧或请求帧，客户端**不得**有 case | 投递前被改写（如 13）；渲染→服务端请求（如 23） |
| `KNOWN_GAP` | 已知「功能未接通」，每次运行都打印保持技术债可见 | 既不落库也无 case 的业务帧 |

新增帧号却未声明 → **阻断**。已登记的 `KNOWN_GAP` 若后来被接通 → **阻断**（提示删除登记）。

## 变异检验（配套脚本）

「脚本存在」不等于「门禁有判别力」。改动帧协议或 WS 逻辑后，必须跑对应变异检验：

```bash
node scripts/verify/mutation_ws_frame_parity.cjs          # 9 条变异，改 3 个源文件
node scripts/verify/mutation_channel_online_status.cjs   # 8 条变异，每次跑一次 mvn test（约 2–3 分钟）
```

两条纪律：

1. **两个脚本都会临时改写源文件再还原**，因此带前置守卫：目标文件有未提交改动时
   直接 `exit 2` 拒绝执行（还原会覆盖你的工作）。
2. **`[SKIP]`（锚点未命中）与 `exit === null`（命令启动失败）一律判失败**。
   只看汇总的 `[CAUGHT]` 会被这些假通过骗过去——首版就因
   Windows 上 `execFileSync('mvn')` 跑不了 `.cmd`（ENOENT → `status=null`）
   而误报「全部捕获」。
