# Proposal — 群文件：上传 / 列表 / 删除 / 下载

- 创建日期: 2026-09-26
- 效率等级: L3

## Why

群聊场景下，成员经常需要共享资料（图片 / 视频 / 文档）。当前系统聊天消息中的文件随会话滚动消失、无法沉淀复用，且没有统一的"群文件"空间。迁移脚本 `easychat-migration-004-im-complete.sql` 已建好 `group_file` 表，但后端无对应接口、前端无入口，能力处于"留白"状态。本变更补齐该能力，构成 IM 完整体验闭环的一环（与聊天记录导出、全局搜索同属四-新增能力方向）。

## What Changes

- 后端: 新增 `GroupFileController`（`/group/file`：`upload` / `list` / `delete`）；新增 `GroupFile` PO、`GroupFileQuery`、`GroupFileMapper`+XML、`GroupFileService`+实现；`Constants` 新增 `FILE_FOLDER_GROUP`；复用既有分片上传 `/upload/uploadChunk` 与 `/upload/checkChunks`，新增 group 专属合并接口；`ChatController.downloadFile` 增加 `partType=group` 下载分支。
- 前端: `Api.js` 新增 `groupFileUpload` / `groupFileList` / `groupFileDelete`；新增 `GroupFileChunkUploadApi.js`（复用分片机制）；新增 `GroupFile.vue` 群文件面板；`Chat.vue` 群会话头部新增"群文件"入口按钮；主进程 `file.js` 的 `getLocalFilePath` 新增 `group` 分支以支持预览/下载缓存。
- 数据库: 无结构变更（`group_file` 表已由迁移脚本建立）；仅新增数据行。

## Capabilities

- C1: 群成员可上传文件到群文件空间（图片 / 视频 / 文档，分片上传，断点续传复用既有机制），上传后写入 `group_file` 并可在群文件列表查看。
- C2: 群成员可分页浏览本群文件列表，按上传时间倒序，可下载（含图片/视频预览）。
- C3: 上传者本人或群主/管理员可删除群文件（逻辑删除 `status=0`），其他成员不可删。
- C4: 非群成员无法读取 / 上传 / 删除群文件（最小权限校验）。

## Impact

- 对外接口: 新增 3 个业务接口（upload / list / delete），下载复用既有 `/chat/downloadFile`（`partType=group`）。前端 `Api.js` 需同步，否则 `check-api-contract` 门禁会报漂移。
- 存量数据: 无。`group_file` 表为空，不影响既有数据。
- 性能 / 安全: 文件落盘沿用既有 `projectFolder/file/group/` 目录，复用本地文件服务缓存；上传校验群成员身份，删除校验归属/管理权限，防越权。
- 回退方案: 纯增量接口与前端组件，回退只需 `git revert` 本次提交；`group_file` 表保留无害。

---

## ☑ 人工确认关卡

> 本提案经 7even（用户） 于 2026-09-26 确认，允许进入 design 阶段。
>
> - [x] 同意方案，允许继续
> - [ ] 需修改（说明：__________________）
> - [ ] 退回重新评估
