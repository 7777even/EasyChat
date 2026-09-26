# QA — 群文件：上传 / 列表 / 删除 / 下载

- 变更: openspec/changes/2026-09-26-group-file
- 日期: 2026-09-26
- 执行人: WorkBuddy

## 1. 范围

后端新增 `GroupFileController`（`/group/file`：upload / list / delete）、`GroupFileService`+实现、`GroupFile` PO/Query/VO、`GroupFileMapper`+XML，`Constants.FILE_FOLDER_GROUP`，`ChatController.downloadFile` 加 `group` 分支；前端新增 `Api.js` 三端点、`GroupFileChunkUploadApi.js`、`GroupFile.vue`、`Chat.vue` 入口、`file.js` 本地服务 `group` 分支。

## 2. 验收口径（对照 spec-delta 场景）

| # | 场景 | 验收预期 | 验证方式 | 结论 |
|---|------|----------|----------|------|
| C1-1 | 群成员上传文件 | 分片合并落盘 `file/group/<fileId>.<ext>`，`group_file` 入库 `status=1`，返回 `Result<GroupFile>` | 代码路径 + 构建 | ✅ |
| C1-2 | 非群成员上传 | 抛 `CODE_2304`（checkGroupRole MEMBER 拦截） | 代码路径 | ✅ |
| C2-1 | 群成员查看列表 | `Result<PaginationResultVO<GroupFileVO>>`，仅 `status=1`，`create_time desc`，含上传人昵称 | 代码路径 + 构建 | ✅ |
| C2-2 | 下载 / 预览 | `<a href="/file?fileId=<stored>&partType=group">` 经本地服务回源 `/chat/downloadFile?partType=group` | 代码路径 | ✅ |
| C3-1 | 上传者删自己的文件 | `status` 置 0，列表不再展示 | 代码路径 | ✅ |
| C3-2 | 非上传者且非管理删除 | 抛 `CODE_1002`，记录不变 | 代码路径 | ✅ |
| C4 | 越权访问 list/upload/delete | 均被 `checkGroupRole(MEMBER)` 拦截，返回 `CODE_2304` | 代码路径 | ✅ |

## 3. 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `mvn compile`（easychat-java） | BUILD SUCCESS（155 源文件） |
| `electron-vite build`（easychat-front） | ✓ built in 24.46s，exit 0 |
| `node scripts/check-ipc-registration.mjs --strict` | 33/33 已注册 ✓ |
| `node scripts/check-api-contract.mjs` | 86 路由 / 84 调用 / 0 漂移（2 孤儿为历史既有 `/chat/downloadFile`、`/update/download`，非本次引入） |
| `node scripts/check-openspec-hygiene.mjs` | 0 错误 / 0 警告 / 0 信息 ✓ |

自动用例：**5 门禁/构建项全过 + 7 场景代码路径审查全过**。

## 4. 未运行项（如实标注）

- **活体 HTTP 冒烟**（upload/list/delete/download + 越权用例）：本沙箱后端未启动（`curl 127.0.0.1:5050` → HTTP 000），且需要有效登录 token。未执行真实网络请求。
- 替代：以 `mvn compile` + `electron-vite build` + 三道门禁 + 逐场景代码路径审查覆盖；逻辑与既有 `memberList` / 聊天文件上传 / 朋友圈媒体上传完全一致（同套分片机制、同套本地文件服务）。

## 5. 手动验证清单（交付用户本地起后端后）

1. 启动后端（MySQL+Redis），前端登录进入一个群聊。
2. 群会话头部点「群文件」图标 → 打开面板。
3. 点「上传文件」选一个图片 → 进度 100% → 列表出现该文件。
4. 点「下载」→ 文件正确下载；图片类型显示缩略图。
5. 退群后用他人账号登录 → 面板看不到该文件；上传被拒。
6. 非上传者点「删除」→ 提示无权限（后端 `CODE_1002`）。

## 6. 结论

群文件能力实现完成，构建与全部门禁通过，7 个 spec 场景经代码路径审查均符合预期。仅活体 HTTP 冒烟因环境（后端未启动 / 需 token）未执行，已由构建+门禁+代码审查替代，风险可控。

## 证据

- 构建/门禁输出快照见同目录 `2026-09-26-group-file-smoke.txt`
