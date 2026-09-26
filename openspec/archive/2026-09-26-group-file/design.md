# Design — 群文件：上传 / 列表 / 删除 / 下载

- 关联 Proposal: 2026-09-26-group-file/proposal.md
- 创建日期: 2026-09-26

## 1. 架构设计

```
群文件面板(GroupFile.vue)
   │  (分片) 复用 ChunkUpload.js → /upload/uploadChunk + /upload/checkChunks
   │  (合并)  GroupFileChunkUploadApi → POST /group/file/upload
   ▼
GroupFileController  ──►  GroupFileService
                               │ 成员校验: groupInfoService.checkGroupRole(userId, groupId, MEMBER)
                               │ 合并分片: FileUploadServiceImpl 通用合并 → projectFolder/file/group/<fileId>.<ext>
                               │ 入库: GroupFileMapper.insert (group_file)
                               │ 列表: GroupFileMapper.selectList (create_time desc, 分页)
                               │ 删除: updateByParam (status=0)
   ▼
下载: 前端 <a href="/file?fileId=<storedName>&partType=group"> → 本地服务 file.js
   → 本地缺文件时回源 POST /api/chat/downloadFile?partType=group&fileId=<storedName>
   → ChatController.downloadFile 读 projectFolder/file/group/<storedName> 返回
```

### 后端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| Controller | 新增 `GroupFileController`（`/group/file`：upload / list / delete），`ChatController.downloadFile` 加 `group` 分支 | 路由 + `@GlobalInterceptor` 鉴权 + 调 Service |
| Service | 新增 `GroupFileService` + 实现（成员校验、合并落盘、分页列表、删除权限） | 事务边界 + 权限校验 + 文件 IO |
| Mapper / XML | 新增 `GroupFileMapper` + `GroupFileMapper.xml`（selectList / selectCount / insert / updateByParam 软删） | 只写 SQL |
| Entity | 新增 `GroupFile` PO、`GroupFileQuery`、`GroupFileVO`（含上传人昵称） | 1:1 对应 `group_file` |
| Constants | 新增 `FILE_FOLDER_GROUP = "group/"` | 文件目录常量 |

### 前端改动

| 模块 | 改动 | 职责边界 |
|------|------|----------|
| 渲染进程 | 新增 `GroupFile.vue` 面板（列表 / 上传进度 / 下载 / 删除） | 仅 UI 与 request 调用 |
| 渲染进程 | `Api.js` 新增 3 个端点常量；`GroupFileChunkUploadApi.js` 复用分片机制 | 请求封装 |
| 渲染进程 | `Chat.vue` 群头部新增"群文件"按钮，打开面板 | 入口 |
| 主进程 | `file.js` 的 `getLocalFilePath` 新增 `group` 分支 | 本地文件缓存路径 |

## 2. 接口设计

| 端点 | Method | 入参 | 出参 | 权限 |
|------|--------|------|------|------|
| `/api/group/file/upload` | POST | `fileId`(MD5) `groupId` `fileName` `totalChunks` `fileType`(0/1/2) `fileSize?` `cover?` | `Result<GroupFile>` | 群成员 |
| `/api/group/file/list` | POST | `groupId` `pageNo?` `pageSize?` | `Result<PaginationResultVO<GroupFileVO>>` | 群成员 |
| `/api/group/file/delete` | POST | `groupId` `fileId`(group_file.id) | `Result<Void>` | 上传者/群主/管理员 |
| `/api/chat/downloadFile`(复用, 加分支) | POST | `fileId`=存储文件名 `showCover=false` `partType=group` | 文件流 | 群成员（token 校验） |

### 错误码

复用现有段：群域 `2302` 不在群组中；新增无需新段，越权删除复用通用 `1002`。成员校验失败抛 `BusinessException(CODE_2302)`。

## 3. 数据模型

无结构变更。`group_file` 表已由 `easychat-migration-004-im-complete.sql` 建立，本变更仅操作其数据。存储路径约定：

- 物理文件：`{projectFolder}/file/group/{fileId}.{ext}`
- `group_file.file_path` 存相对文件名：`{fileId}.{ext}`
- `group_file.file_type`：0 图片 / 1 视频 / 2 文件（与既有 file_type 口径一致）

## 4. 安全设计

- 鉴权: 三个新接口均 `@GlobalInterceptor`，必须登录。
- 数据权限: 上传/列表/删除前均校验当前用户为群成员（`checkGroupRole(..., MEMBER)`）；删除额外校验 `upload_user_id == userId` 或群主/管理员。
- 输入校验: `groupId`/`fileName`/`fileId` 用 `@NotEmpty`；`totalChunks`/`fileType` 用 `@NotNull`。
- SQL 注入防护: 全部 MyBatis `#{}`；列表条件由 Query 拼接固定列，无字符串拼接。
- 文件安全: 复用既有 `FILE_FOLDER_GROUP` 目录隔离，不覆盖聊天消息文件。

## 5. ADR（架构决策记录）

### ADR-001: 复用既有分片上传，新增 group 专属合并接口

- 状态: 已接受
- 上下文: 既有 `/upload/uploadChunk`、`/upload/checkChunks` 已按 `temp/<userId>/<fileId>/` 存分片，但 `mergeChunks` 绑定 `messageId` 命名，不适用于群文件。
- 决策: 前端分片阶段仍复用 `/upload/*`，新增 `/group/file/upload` 负责合并到 `file/group/` 并入库；逻辑集中在 `FileUploadService` 新增 `mergeGroupFile` 方法。
- 后果: 分片存储复用，避免重复代码；代价是多一个合并端点，但语义清晰。

### ADR-002: 下载走既有 `/chat/downloadFile` + 本地服务 `partType=group`

- 状态: 已接受
- 上下文: 群文件预览/下载与聊天文件、朋友圈媒体同属"按 fileId 取盘文件"模式，本地服务已支持 avatar/chat/moment 三分支。
- 决策: `ChatController.downloadFile` 加 `group` 分支；`file.js` 的 `getLocalFilePath` 加 `group` 分支；不新增独立下载端点，保持契约最小化。
- 后果: 预览/下载零新增路由、与既有体验一致；下载仍带 token 校验。

## 6. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 分片目录残留（合并失败） | 低 | 低 | 合并成功后 `finally` 清理 `temp/<userId>/<fileId>/`，与既有实现一致 |
| 越权访问群文件 | 低 | 高 | 所有接口前置 `checkGroupRole` 成员校验 + 删除归属/管理校验 |
| 大文件 OOM | 低 | 中 | 复用分片流式合并（8KB buffer），不一次性读入内存 |
| 契约门禁漂移 | 中 | 低 | 前端 `Api.js` 同步新增端点，跑 `check-api-contract` 验证 |

## 7. 依赖与前提

- `group_file` 表已存在（迁移脚本 004）。
- 既有 `FileUploadService`、`ChunkUpload.js`、`file.js` 本地服务可用。
- 无跨 Change 依赖。
