# Tasks：敏感词库管理端

- 关联 Design: 2026-09-26-sensitive-word-admin/design.md
- 创建日期: 2026-09-26
- 预估总工时: 8h

> 任务按实施顺序排列；单条 ≤2h。
> [TDD] 标记的任务必须先写失败测试再实现。本变更无既有后端测试目录，后端 TDD 以「先写失败断言的活体冒烟用例（`smoke_sensitive_word.py`，不入库）」代替单测；前端以先写失败的接口契约核对为准。

## 前置（L4 关卡）

- [x] **L4 二次人工确认**：proposal 中 ①delete_flag BIGINT 时间戳语义 ②2704/2705 新错误码，两项获得人工点头 — ≤10min
- [x] 四件套（proposal/design/tasks/spec-delta/.openspec.yaml）完成并经人工确认关卡 — ≤1h

## 阶段一：数据库与实体

- [x] **[TDD]** migration-007（`delete_flag` 字段 + `uk_word_flag` 唯一索引）+ 同步 `easychat.sql` 基线 — ≤1h
- [x] **[TDD]** Entity `SensitiveWord` 补 `deleteFlag`；`SensitiveWordMapper` + XML（分页筛选/selectByWord/逻辑删除/批量插入，**全部 `bean.` 前缀**） — ≤2h

## 阶段二：服务与接口

- [x] **[TDD]** `SensitiveWordAdminService`：查重转 2704、删除校验 2705、导入解析（txt/csv 逐行容错 + 计数 + 重复跳过）、导出 csv（BOM + 公式注入防护）、写后调 `reload()` — ≤2h
- [x] **[TDD]** 修改 `SensitiveWordServiceImpl` 加载 SQL 加 `AND delete_flag=0` — ≤30min
- [x] **[TDD]** `ResponseCodeEnum` 新增 2704/2705；`AdminSensitiveWordController` 五端点（checkAdmin + @Valid + 1001 文件校验） — ≤1h
- [x] 后端 `mvn compile` 通过 — ≤30min

## 阶段三：前端

- [x] **[TDD]** `views/admin/SensitiveWord.vue`（筛选列表 + 增改删 + 导入 dialog 回显计数 + 导出按钮） — ≤2h
- [x] `Admin.vue` 菜单 + `router` 子路由 + `Api.js` 5 端点；`request.js` 包络对齐 `Result<T>`（导出走文件流单独处理） — ≤1h

## 阶段四：验证

- [x] 手动执行 migration-007，活体确认 `delete_flag` 与唯一索引存在 — ≤10min
- [x] 后端启动 + 活体冒烟：新增/重复2704/编辑/删除2705/**删后重导成功**（唯一索引语义）/导入 txt+csv 跳过计数/导出 csv 原样导回/筛选用已导入词实测 2701 拦截与 level2 替换/删除后该词立即失效（`delete_flag=0` 加载断言）/非管理员 404 — ≤2h
- [x] 前端 `electron-vite build` + `check-api-contract`（5 新路由 0 漂移）+ `check-openspec-hygiene` — ≤30min

## 阶段五：收尾

- [x] QA/Retro 落 `engineering/`（L4 必写，附冒烟存证） — ≤30min
- [x] spec-delta 回写 `openspec/specs/` + `git mv` 归档 + 按域分笔提交推送 — ≤30min

## 验收标准（↔ proposal Capabilities）

- [x] C1 管理员可分页筛选并增改删词条；重复新增返回 2704、删不存在词条返回 2705
- [x] C2 txt/csv 批量导入返回 新增/跳过/失败 计数，同词不重复入库
- [x] C3 导出 csv 可原样导回（往返等价，带 BOM 与公式注入防护）
- [x] C4 保存/删除/导入后自动 reload：新词即刻触发 2701/替换，已删词即刻失效
- [x] C5 删掉的词可重新导入/新增（唯一索引不被已删行占用）
- [x] 非管理员调用五接口均被 checkAdmin 拦截（404）
- [x] mvn compile / electron-vite build / check-api-contract / check-openspec-hygiene 全绿

## DoD 自检（完成后逐项确认）

- [x] `openspec/changes/2026-09-26-sensitive-word-admin/tasks.md` 全部勾选
- [x] 按 AGENTS.md §2 矩阵执行，mvn compile 0 error（L4 行含启动验证）
- [x] `easychat.sql` 已同步字段与索引；前端调用方（Api.js）已同步
- [x] 归档闭环完成（spec-delta 回写 specs/ + git mv 到 archive/）
- [x] QA / Retro 记录已落 `engineering/`
