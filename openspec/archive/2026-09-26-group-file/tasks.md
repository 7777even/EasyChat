# Tasks — 群文件：上传 / 列表 / 删除 / 下载

- 关联 Design: 2026-09-26-group-file/design.md
- 创建日期: 2026-09-26
- 预估总工时: 4h

> 任务按实施顺序排列；单条 ≤2h。

## 阶段一：基础结构（后端数据层）

- [x] 新增 `GroupFile` PO、`GroupFileQuery`、`GroupFileVO`（`entity/po`、`entity/query`、`entity/vo`） — ≤1h
- [x] 新增 `GroupFileMapper.java` + `GroupFileMapper.xml`（selectList / selectCount / insert / updateByParam 软删 / base_column） — ≤1h
- [x] `Constants` 新增 `FILE_FOLDER_GROUP = "group/"` — ≤10min

## 阶段二：业务与接口

- [x] 新增 `GroupFileService` 接口 + 实现（成员校验、分片合并落盘、分页列表、删除权限） — ≤2h
- [x] 新增 `GroupFileController`（`/group/file`：upload / list / delete，均 `@GlobalInterceptor`） — ≤1h
- [x] `ChatController.downloadFile` 增加 `partType=group` 下载分支 — ≤30min
- [x] 后端 `mvn compile` 通过 — ≤30min

## 阶段三：前端适配

- [x] `Api.js` 新增 `groupFileUpload` / `groupFileList` / `groupFileDelete` — ≤15min
- [x] 新增 `GroupFileChunkUploadApi.js`（复用分片机制，merge 指向 `/group/file/upload`） — ≤30min
- [x] 新增 `GroupFile.vue` 面板（列表 / 上传进度 / 下载 / 删除） — ≤2h
- [x] `Chat.vue` 群头部新增"群文件"按钮并打开面板；`file.js` 的 `getLocalFilePath` 加 `group` 分支 — ≤1h
- [x] 前端 `electron-vite build` 通过 — ≤30min

## 阶段四：验证与同步

- [x] 门禁：`check-api-contract` / `check-openspec-hygiene` / `check-ipc-registration` 全绿 — ≤30min
- [x] 后端启动 + 接口冒烟（upload/list/delete/download 覆盖 + 越权用例）— ≤1h
- [x] 同步 `engineering/qa/2026-09-26-group-file.md`（含 curl 证据）— ≤30min

## 阶段五：收尾

- [x] 同步 `engineering/retro/2026-09-26-group-file.md` — ≤30min
- [x] spec-delta 回写 `openspec/specs/group-file/spec.md` + `git mv` 归档 Change — ≤30min
- [x] `docs/system-facts.md` 记录群文件能力 — ≤15min

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-26-group-file/tasks.md` 全部勾选
- [x] `mvn compile` 0 error；前端 `electron-vite build` 0 error
- [x] 契约同步：前端 `Api.js` 与后端路由一致，`check-api-contract` 无漂移
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
