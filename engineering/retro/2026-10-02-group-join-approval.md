# Retro — 群入群审批闭环

- Change: `openspec/changes/2026-10-02-group-join-approval/`
- 日期: 2026-10-02

## 一、做得好

1. **先摸清既有链路再动手，没新建审批机制**。`UserContactApplyService#applyAdd` 早已完整实现 `join_type` 分流，缺的只是两条"野生"入群路径没走它。收编为一条真源 → 零 DDL、零迁移、零新端点、审批 UI 自动复用。ADR-001 记下了这个判断。
2. **写 design 时先验证实现，逮到 3 个会踩的坑**。原 design 假设"把管理员群 ID 集合作为 `IN` 条件入参"——读 XML 后发现 `query_condition` 只支持等值；更关键的是发现 `queryContactInfo` 的两个 LEFT JOIN 里内嵌了 `and a.receive_user_id = #{query.receiveUserId}`，一旦改条件就会退化成 `= NULL`，**所有行的 `contactName` 变 null**。这个雷如果在 QA 才发现，排查成本极高。这三个核查点已写进 design §1「已核查的实现细节」。
3. **顺藤摸出 3 个你没提但必须一起修的缺口**：
   - `dealWithApply` 硬性要求 `userId == receiveUserId == 群主` → 管理员**无权**审批
   - `loadApply` 只按 `receive_user_id=我` 过滤 → 管理员**看不到**申请（只放开前者就是半个功能）
   - `ChannelContextUtils` WS INIT 的申请红点是**第二处**同口径统计 → 只改 `loadApply` 会「列表有申请但红点不亮」
   这三条都不是用户报的，是顺着数据流走出来的。**"只修用户报的那一处"通常会漏。**
4. **TDD 的红是真红**。`GroupJoinApplyTest` 失败信息是 `Wanted but not invoked ... zero interactions with this mock` —— 一眼看清 `applyAdd` 从未被调用。比起"断言失败"，这直接指出了根因。
5. **冒烟脚本自己审自己**。48 条断言里有 6 条是**负面/幂等断言**（普通成员不可见不可审、被拒后不入群、重复扫码不新增行、好友申请未被放宽、错误分支不误报、清理彻底）。正面路径谁都会写，能挡住回归的是负面断言。
6. **测试数据造得真实才测得出东西**。第 8 段一开始我把好友申请的 `contact_id` 填成群 ID，测出 `1002` 系统错误——不是产品 bug，是我造了现实中不存在的脏数据。按 `applyAdd` 对 USER 类型的既有约定（`receiveUserId = contactId = 被添加方`）改正后立刻通过。

## 二、问题

1. **我在批次 1 交付了一个永远失败的 CI**。CI 前端 job 跑 `npx eslint .`，而这个命令**改动前就是 exit 2**（`No files matching the pattern`），显式 glob 则有 311 errors / 20537 warnings。也就是说 `npm run lint` 从来没真正工作过，我把它当闸门接进 CI，等于交付一个必红的流水线。**这是本项目里"CI 变绿"最容易骗过人的地方：门禁能跑 ≠ 门禁有判别力。**
2. **本机只有 2 个账号能用统一测试口令登录**。四象限（群主/管理员/普通成员/申请者）凑不齐，一度让冒烟无法设计。解法是拆两个临时群 + 用 SQL 造申请单、把不可登录账号当"只断言 DB 结果的申请人"。能绕，但绕的代价是脚本复杂度上升。
3. **单测里用反射注入 `@Resource` 私有字段**。`GroupJoinApplyTest` 里手写了 `set(target, field, value)`。能跑，但脆弱：字段改名则 `IllegalStateException`，且 `@InjectMocks` 与手工注入混用风格不统一。
4. **`loadApply` 不按 status 过滤**。冒烟第 2 段我断言"已处理申请不再出现在待处理列表"直接失败——查下去发现该接口**本来就不按状态过滤**，返回全部。我的断言错了，不是代码错。但这暴露一个既有问题：**申请列表把已处理的也混在一起，前端靠 `item.status == 0` 自行区分**，数据量大时列表会越来越长。
5. **PowerShell 反复毁掉我的文件**。`Set-Content -Encoding UTF8` 造成三宗事故：BOM 让 javac 报 `非法字符 '\ufeff'`；正则批量替换产生重复行；`-replace` 把 LF 弄丢导致行粘连。改用 edit/write 工具后问题消失。结论：**在 Windows 上不要用 PowerShell 改源码**。
6. **前端 40 条新 CRLF 告警**。我改动的两个文件新增 40 条 `prettier: Delete ␍`，与基线已有的 580 条同类。仓库文件以 CRLF 存储而 prettier 未设 `endOfLine`，这个错配是全局性的。

## 三、原因

1. 批次 1 我**假设**了 `npm run lint` 能用（package.json 里有这个 script 就当它能用），没在交付前实跑一次。CI 类交付物必须**实跑一次**才能算验证过。
2. 环境是历史遗留的测试数据，账号体系从未被整理成"可复现的测试夹具"。
3. 项目测试基线只有 4 个测试类，缺 `@InjectMocks` + 反射注入的既有范式可抄。
4. 该接口设计时只考虑了"待处理"场景，红点统计另走 `ChannelContextUtils` 也没提需求，默认"列表=待处理"。
5. 对 PowerShell 在 Windows 上写文件的副作用（编码/换行/正则转义）缺乏肌肉记忆。
6. prettier 配置（无 `endOfline`）与 git 的 `core.autocrlf` 从未对齐过。

## 四、改进方案

| 方案 | 落点 | 优先级 |
|------|------|--------|
| **修 CI lint 步骤**（已当场移除并写明原因与恢复条件） | `.github/workflows/ci.yml` | ✅ 已完成 |
| 前端 lint 分两步走：① 先 `--fix` 清掉 20525 条可自动修复的 warning ② 311 个 error 逐文件收敛 | 独立 L2 Change | 高 |
| 恢复 CI lint 的条件：先让 `eslint .` 能正常枚举文件（补 `overrides`/`ignorePatterns`），且 error 数降到 0 | 同上 | 高 |
| 建可复现测试夹具：3–4 个专用测试账号（`smoke_admin@` / `smoke_user1@` / `smoke_user2@` / `smoke_guest@`），统一口令，写进 `scripts/smoke/README.md`，CI 侧用 docker compose 起 MySQL/Redis + 种子脚本 | 独立 Change | 高（CI 活体验证的前提） |
| `loadApply` 增加 `status` 过滤参数（默认只返回待处理，红点与列表口径再对齐一次） | 独立 L3 | 中 |
| 抽一个测试基类提供反射注入工具（`@InjectMocks` 统一范式） | `src/test` 基建 | 中 |
| prettier 加 `endOfLine: 'crlf'` 与 git 对齐，或统一 `core.autocrlf=false` + 仓库存 LF | `.prettierrc.yaml` | 中 |
| **硬纪律：任何写入 CI / hook / 门禁的脚本，交付前必须实跑一次并贴 exit code** | 写进 `AGENTS.md` §2 验证矩阵 | 立即 |
| 硬纪律：Windows 上一律用 edit/write 工具改源码，不用 PowerShell 重定向 | 写进 `AGENTS.md` §6.3 | 立即 |
| 补 `verify_group_join_contract.mjs` 门禁：静态断言 `joinByQrCode`/`joinByInvite` 不直接调 `addContact` | `scripts/verify/` | 中（防同类回归） |

## 五、给下一批的经验

- **门禁要有判别力**：`eslint .` 存在 ≠ 有效。接进 CI 前先问"它现在能通过吗？"
- **顺着数据流走一遍**，用户报的问题通常只是链条上的一环。本例 1 个洞牵出 3 个连带缺口。
- **造测试数据要符合业务语义**。不真实的数据会造出假 bug，浪费一整轮排查。
- **正面路径易写，负面断言才拦回归**。每个新权限点至少配一条「无权访问被拒」+ 一条「幂等不重复」。
- **不确定的设计假设，先读实现再写进 design**。XML 里内嵌条件这种地雷，QA 阶段发现和 design 阶段发现成本差 10 倍。
