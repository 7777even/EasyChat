# QA — 群文件：上传 / 列表 / 删除 / 下载

- 变更: openspec/changes/2026-09-26-group-file
- 日期: 2026-09-26
- 执行人: WorkBuddy

## 1. 范围

后端新增 `GroupFileController`（`/group/file`：upload / list / delete）、`GroupFileService`+实现、`GroupFile` PO/Query/VO、`GroupFileMapper`+XML，`Constants.FILE_FOLDER_GROUP`，`ChatController.downloadFile` 加 `group` 分支；前端新增 `Api.js` 三端点、`GroupFileChunkUploadApi.js`、`GroupFile.vue`、`Chat.vue` 入口、`file.js` 本地服务 `group` 分支。

## 2. 验收口径（对照 spec-delta 场景）

| # | 场景 | 验收预期 | 验证方式 | 结论 |
|---|------|----------|----------|------|
| C1-1 | 群成员上传文件 | 分片合并落盘 `file/group/<fileId>.<ext>`，`group_file` 入库 `status=1`，返回 `Result<GroupFile>` | **活体冒烟** + 代码路径 + 构建 | ✅ |
| C1-2 | 非群成员上传 | 抛 `CODE_2304`（checkGroupRole MEMBER 拦截） | **活体冒烟** + 代码路径 | ✅ |
| C2-1 | 群成员查看列表 | `Result<PaginationResultVO<GroupFileVO>>`，仅 `status=1`，`create_time desc`，含上传人昵称 | **活体冒烟** + 代码路径 + 构建 | ✅ |
| C2-2 | 下载 / 预览 | `<a href="/file?fileId=<stored>&partType=group">` 经本地服务回源 `/chat/downloadFile?partType=group` | 代码路径（手动待确认） | ✅ |
| C3-1 | 上传者删自己的文件 | `status` 置 0，列表不再展示 | **活体冒烟** + 代码路径 | ✅ |
| C3-2 | 非上传者且非管理删除 | 抛 `CODE_1002`，记录不变 | 代码路径（手动待确认） | ✅ |
| C4 | 越权访问 list/upload/delete | 均被 `checkGroupRole(MEMBER)` 拦截，返回 `CODE_2304` | **活体冒烟**（list/upload）+ 代码路径 | ✅ |

## 3. 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `mvn compile`（easychat-java） | BUILD SUCCESS（155 源文件） |
| `electron-vite build`（easychat-front） | ✓ built in 24.46s，exit 0 |
| `node scripts/check-ipc-registration.mjs --strict` | 33/33 已注册 ✓ |
| `node scripts/check-api-contract.mjs` | 86 路由 / 84 调用 / 0 漂移（2 孤儿为历史既有 `/chat/downloadFile`、`/update/download`，非本次引入） |
| `node scripts/check-openspec-hygiene.mjs` | 0 错误 / 0 警告 / 0 信息 ✓ |
| `python scripts/smoke/smoke_group_file.py`（活体 HTTP 冒烟，后端 5050 + MySQL + Redis 真实链路） | **23/23 PASS**（21:13 首跑；23:33 复跑一致） |

自动用例：**5 门禁/构建项全过 + 7 场景代码路径审查全过 + 活体冒烟 23 项全过**。

活体冒烟覆盖（脚本 `scripts/smoke/smoke_group_file.py`，自清理、不污染数据）：
分片 `uploadChunk` → `checkChunks` 注册 → `/group/file/upload` 合并入库（`status=1`、落盘名 `fileId.txt`）→ `/group/file/list` 列表含上传人昵称 → `/group/file/delete` 逻辑删除（`status=0`、列表不再出现）→ 非成员 list/upload 均拦截 `2304` → 收尾清理成员行与群文件行。

## 4. 未运行项（如实标注）

活体冒烟已执行（见上）。仍未覆盖、需本机手动确认的仅剩两项（均不涉及本次新增的越权链路）：

- **C2-2 下载 / 预览**：`<a href="/file?fileId=<stored>&partType=group">` 经本地文件服务回源 `/chat/downloadFile?partType=group` 的真实字节流未在脚本中断言，仅走代码路径审查。
- **C3-2 非上传者且非管理员删除** 应抛 `CODE_1002`：脚本未构造「同群非上传者」第二账号，仅走代码路径审查。
- 说明：早期 QA 结论曾记「活体冒烟因后端未启动未执行」，该结论已于 2026-09-26 21:13 起被真实执行推翻，本节按实际结果回填。

## 5. 手动验证清单（交付用户本地起后端后）

1. 启动后端（MySQL+Redis），前端登录进入一个群聊。
2. 群会话头部点「群文件」图标 → 打开面板。
3. 点「上传文件」选一个图片 → 进度 100% → 列表出现该文件。
4. 点「下载」→ 文件正确下载；图片类型显示缩略图。
5. 退群后用他人账号登录 → 面板看不到该文件；上传被拒。
6. 非上传者点「删除」→ 提示无权限（后端 `CODE_1002`）。

## 6. 结论

群文件能力实现完成：构建与全部门禁通过；7 个 spec 场景中 **5 个（C1-1 / C1-2 / C2-1 / C3-1 / C4）已由活体 HTTP 冒烟在真实链路验证通过（23/23 PASS，且复跑一致）**，剩余 C2-2 下载预览、C3-2 非上传者删除两项经代码路径审查符合预期、留本机手动确认。整体风险可控，可交付。

## 证据

- 构建/门禁输出快照见同目录 `2026-09-26-group-file-smoke.txt`
- 活体冒烟输出（21:13 首跑，23/23 PASS）见同目录 `2026-09-26-group-file-live-smoke.txt`
- 活体冒烟复跑输出（23:33，23/23 PASS）见同目录 `2026-09-26-group-file-live-smoke-rerun.txt`（注：`*.log` 被 .gitignore 忽略，故证据统一用 .txt）
