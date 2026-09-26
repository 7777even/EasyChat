# Retro — 群文件：上传 / 列表 / 删除 / 下载

- 变更: openspec/changes/2026-09-26-group-file
- 日期: 2026-09-26

## 做得好

- **复用到位**：分片上传复用既有 `/upload/uploadChunk`+`/upload/checkChunks`，下载复用本地文件服务 `partType` 分支与 `/chat/downloadFile`，未新增冗余下载端点，契约零漂移。
- **权限闭环**：所有接口前置 `groupInfoService.checkGroupRole(MEMBER)`，删除额外校验上传者/群主/管理员，符合最小权限。
- **门禁全绿**：后端 `mvn compile`、前端 `electron-vite build`、IPC/契约/openspec 三道门禁一次通过。
- **四件套闭环**：proposal/design/tasks/spec-delta 完整，spec 与 tasks 状态一一对应。

## 问题

- `CopyTools.copy` 初版误用 `(source, targetInstance)`，实际签名是 `(source, Class<T>)`，导致编译失败一次。
- `GroupInfoServiceImpl.checkGroupRole` 原为 `private`，跨服务调用 `GroupFileService` 时不可见，需提升为公开接口方法。

## 原因

- 对 `CopyTools` 与 `checkGroupRole` 的可见性/签名未先查源码，凭直觉写了调用。
- 上一轮新增群文件能力时未把"跨服务成员校验"作为独立公开方法预留。

## 改进方案

- 跨服务复用的方法（如成员角色校验）应在一开始就设计为 public 接口方法，而非 private 工具方法。
- 写调用前先 `Grep`/读签名，避免凭印象传参。
- 下次新增"群内 X 能力"时，成员校验直接走 `checkGroupRole`，无需重复造轮子。
