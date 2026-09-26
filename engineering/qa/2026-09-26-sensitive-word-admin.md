# QA：敏感词库管理端（sensitive-word-admin）

- **范围**：`2026-09-26-sensitive-word-admin` Change —— 词库分页筛选 / 增改删（逻辑删除）/ txt+csv 批量导入 / csv 导出往返 / 写后自动热更，前后端全量落地。
- **能力归属**：`content-moderation`（新增「词库管理」子能力 + 修改引擎加载范围）。
- **分级**：**L4**（`sensitive_word` 表加字段 + 换唯一索引、`ResponseCodeEnum` 新增 2704/2705，命中 AGENTS.md §8）。两道人工关卡（提案准入 + L4 二次实施确认）均已由用户于 2026-09-26 明确通过（「两关都过，开工」）。

## 验收口径（来自 tasks.md ↔ proposal C1–C5）

1. **C1** 管理员可分页筛选并增改删词条；重复新增返回 2704、删不存在词条返回 2705
2. **C2** txt/csv 批量导入返回 新增/跳过/失败 计数，同词不重复入库
3. **C3** 导出 csv 可原样导回（往返等价，带 BOM 与公式注入防护）
4. **C4** 保存/删除/导入后自动 reload：新词即刻触发 2701/替换，已删词即刻失效
5. **C5** 删掉的词可重新导入/新增（唯一索引不被已删行占用）
6. 非管理员调用五接口均被 checkAdmin 拦截（404）
7. mvn compile / electron-vite build / check-api-contract / check-openspec-hygiene 全绿

## 实际执行命令与用例数

| 验证项 | 命令 / 动作 | 结果 |
|---|---|---|
| DB 迁移 | `mysql ... -e "source easychat-migration-007-sensitive-word-admin.sql"` | 已执行；活体表确认 `delete_flag` BIGINT + `uk_word_flag(word,delete_flag)`，旧 `uk_word` 已移除 |
| 后端编译 | `mvn -q compile` | exit=0，0 error |
| 前端构建 | `npm run build`（electron-vite） | exit=0，`SensitiveWord-1c1f36db.js 23.90 kB` chunk 产出 |
| 接口契约门禁 | `node scripts/check-api-contract.mjs --strict` | exit=0；97 路由 / 95 调用 / **0 漂移** / 2 历史孤儿（download 类，非本次） |
| openspec 卫生 | `node scripts/check-openspec-hygiene.mjs` | exit=0；0 错 / 0 警 / 0 信息 |
| 活体冒烟 | `python smoke_sensitive_word.py <存证>` | **49/49 PASS**（脚本按约定不入库） |

证据见同目录：
- `2026-09-26-sensitive-word-smoke.txt` —— 活体冒烟全量输出（49 项逐条 PASS）
- `2026-09-26-sensitive-word-evidence.txt` —— 编译/构建/双门禁/DB 结构快照

## 活体冒烟覆盖矩阵（49 项）

| 组 | 覆盖点 | 关键断言 |
|---|---|---|
| 0 前置（4） | 迁移落库 | `delete_flag` 列、`uk_word_flag` 双列索引存在、旧 `uk_word` 已移除 |
| 1 C1（9） | 新增/重复/编辑/改名撞名/删不存在/筛选 | 重复新增 `2704`；编辑不存在 `2705`；编辑落库 `level=2 status=0`；keyword+level+status 组合筛选命中；列表 VO 不泄漏 `deleteFlag` |
| 2 C5（4） | 逻辑删除 + 删后重增 | 删除后行仍在且 `delete_flag>0`（非物理删）；删后重增同名词 `code=0` 且存活 1 行 —— **唯一索引不被已删行占用** |
| 3 C2（8） | txt / csv / 三类超限 | txt 三态 `3/1/1`；csv 三态 `2/1/2`（含表头跳过、`level=9` 越界计失败、文件内重复计跳过）；csv 级别状态按列落库；>5000 行 / 非 `.txt/.csv` / >2MB 均 `1001` |
| 4 C3（9） | 导出 + 往返 | HTTP 200 文件流、UTF-8 BOM、表头 `word,level,status`、已删词不在导出、存活词唯一、**`=SUM` 开头词被前置 `'` 防公式注入**、导出恒 3 列、原样导回 `success=0/skipped=7`、往返后存活词数 `7→7` |
| 5 C4（6） | 写后热更三分支 | 新增 level3 后发布被拦 `2701` 且未入库；新增 level2 后内容被替换为 `***` 正常送达；删除 level3 后立即失效（`code=0`）；**导入后新词同样立即生效** |
| 6 权限（4） | 隔离 | 非管理员调 loadWord / deleteWord → `404`；无 token 调 loadWord → `901`；无 token 导出文件流 → `901` |
| 7 收尾（2） | 基线 | fixture 词与 fixture 朋友圈全量清理，存活词 0 行（回归基线） |

## 代码路径核对（逐场景）

- **查重**：`SensitiveWordAdminServiceImpl.saveWord` 先 `selectAliveByWord`（仅 `delete_flag=0`）→ 命中抛 `CODE_2704`；`insert` 撞唯一索引时捕 `DuplicateKeyException` 同样转 2704（并发兜底）
- **逻辑删除**：`deleteById` `SET delete_flag=毫秒时间戳 WHERE delete_flag=0`，删后 `reload()`
- **导入**：一次性 `selectAllAlive()` 载入 HashSet 查重（避免 5000 次点查）；分片 `insertBatch(500)`，单片撞唯一键回退逐行、冲突行计 `skipped`；行级容错（列数/长度/level 1-3/status 0-1）逐行计数不中断
- **导出防护**：`csvField()` 对 `=+-@` 开头值前置 `'`，含 `,`/`"`/换行时加引号并转义；**导入侧对 `'` + `=+-@` 开头的值剥离该引号**，保证往返等价（冒烟第 4 组实测 `success=0`）
- **引擎热更**：三个写入口（save/delete/import 有成功行）末尾统一调 `SensitiveWordService.reload()`
- **加载范围**：`SensitiveWordMapper.xml#selectByStatus` 已加 `AND delete_flag = 0`（`@PostConstruct` 与 `reload()` 共用同一语句）——已删/停用词不进内存过滤树
- **权限**：`AdminSensitiveWordController` 五端点均 `@GlobalInterceptor(checkAdmin = true)`
- **前端**：`views/admin/SensitiveWord.vue`（筛选 + 增改删 + 导入 dialog 回显三态计数 + 导出走 `responseType:'blob'` 下载）；`Api.js` 5 端点；`router` 子路由 `sensitiveWord`；`Admin.vue` 菜单「敏感词管理」

## 未运行项（如实标注）

- **前端 UI 未做 Electron 内运行验证/页面截图**：构建通过 + SFC chunk 产出确认；与 report-admin QA 同口径 —— Electron 桌面 UI 无自动化截图链路，交互逻辑以代码审查 + 后端活体冒烟确认。
- **`word` 长度上限按 DB 实际 `varchar(50)` 落地**（design §4 写的 ≤64 系笔误，以 50 为准，避免超长被 DB 截断/报错）；导入与新增两侧均按 50 校验。

## 冒烟发现并修复的问题

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 导出文件原样导回会多插一行 `'=SUM...`（往返不等价） | 导出为防 Excel 公式注入给 `=+-@` 开头值加了 `'`，导入侧未剥离 | 导入解析时对 `'` + `=+-@` 开头的值剥离该引号（`SensitiveWordAdminServiceImpl`），往返实测 `success=0` |
| 2 | 冒烟 8 项断言误报 FAIL（功能实际正确） | 断言基座 `code_of()` 未处理 `post()` 返回的 `(status, json)` 元组 | 修正脚本 `code_of/data_of` 兼容元组；复跑 49/49（脚本不入库） |

## 结论

迁移落库、后端编译、前端构建、双门禁全绿；活体冒烟 **49/49 PASS**，C1–C5 五条验收口径 + 权限隔离逐条覆盖（含唯一索引删后重导、公式注入防护、往返等价、热更三分支）。**功能具备交付条件。** 前端 Electron UI 可见性交互仍以构建 + 代码审查确认（见未运行项）。

## 手动验证清单（上线前）

1. MySQL 执行 `easychat-migration-007-sensitive-word-admin.sql`（本机已执行）
2. 管理员登录 → 管理端 → 敏感词管理：看到词条列表（当前基线 0 行）
3. 点「新增词条」填一个 level=3 词 → 保存 → 用普通账号发含该词消息 → 被拒「内容包含敏感词」
4. 点「删除」该词条 → 再发同内容消息 → 正常送达
5. 点「批量导入」传一个含重复词的 `.csv` → 回显「新增/跳过/失败」三态计数
6. 点「导出 CSV」→ 得到带 BOM 的 `sensitive-words.csv`，Excel 打开中文不乱码
7. 非管理员 token 调 `/admin/sensitiveWord/loadWord` → CODE_404

> 注：1、3、4、5、6、7 的接口侧已由活体冒烟覆盖（`...-smoke.txt`）；剩余待人工项仅为 Electron UI 的可见性交互（2 的列表展示、3/4 的前端提示文案）。
