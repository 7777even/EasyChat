# Spec — 群文件（group-file）

> 能力来源：openspec/changes/2026-09-26-group-file（已归档）

## Requirement: 群文件上传（C1）

群成员可将图片 / 视频 / 文档以分片方式上传到群文件空间，上传成功后写入 `group_file` 并在列表中可见。

#### Scenario: 群成员上传文件

- **WHEN** 群成员调用 `POST /group/file/upload`（携带已上传分片的 `fileId`、群组 `groupId`、文件名、分片数、文件类型）
- **THEN** 服务端合并分片到 `{projectFolder}/file/group/{fileId}.{ext}`，向 `group_file` 插入一行（`status=1`），返回 `Result<GroupFile>`
- **AND** 该文件立即出现在群文件列表中

#### Scenario: 非群成员上传被拒

- **WHEN** 非本群成员调用上传接口
- **THEN** 抛出 `CODE_2304`（不在群组中），不写入任何文件

---

## Requirement: 群文件列表（C2）

群成员可分页浏览本群文件，按上传时间倒序，并支持下载与图片/视频预览。

#### Scenario: 群成员查看列表

- **WHEN** 群成员调用 `POST /group/file/list`（`groupId`，可选分页）
- **THEN** 返回 `Result<PaginationResultVO<GroupFileVO>>`，仅含 `status=1` 的文件，按 `create_time desc`
- **AND** 每条含上传人昵称，供前端展示

#### Scenario: 下载 / 预览

- **WHEN** 前端以 `partType=group`、`fileId=存储文件名` 请求本地服务 `/file` 或后端 `/chat/downloadFile`
- **THEN** 返回对应物理文件流（图片/视频可预览，其他触发下载）

---

## Requirement: 群文件删除（C3）

上传者本人或群主/管理员可删除群文件（逻辑删除）。

#### Scenario: 上传者删除自己的文件

- **WHEN** 上传者调用 `POST /group/file/delete`（`fileId`=group_file.id）
- **THEN** 该记录 `status` 置 0，列表不再展示

#### Scenario: 非上传者且非管理删除被拒

- **WHEN** 普通成员尝试删除他人上传的文件
- **THEN** 抛出业务异常（`CODE_1002`），记录不变

---

## Requirement: 群文件最小权限（C4）

所有群文件接口均强制群成员校验。

#### Scenario: 越权访问

- **WHEN** 非群成员调用 list / upload / delete 中任一接口
- **THEN** 均被 `checkGroupRole(MEMBER)` 拦截，返回 `CODE_2304`
