# Spec Delta — 敏感词库管理端

- 关联 Tasks: 2026-09-26-sensitive-word-admin/tasks.md
- 创建日期: 2026-09-26

> 格式对齐 `openspec/specs/content-moderation/spec.md`。
> 与 proposal Capabilities C1–C5 一一对应。
> 本 delta 扩展既有 `content-moderation` capability（敏感词引擎子能力），新增「词库管理」子能力。

## ADDED Requirements

### Requirement: 词库筛选与维护（word-admin-crud）— C1

管理员（token `admin=true`）必须能够分页筛选并维护敏感词库。

#### Scenario: 管理员分页筛选词库

- **WHEN** 管理员调用 `POST /admin/sensitiveWord/loadWord`（可选 `keyword/level/status` + `pageNum/pageSize`）
- **THEN** 返回 `Result<PageResult<SensitiveWordVO>>`，只含 `delete_flag=0` 的存活词条
- **AND** 按 `create_time` 倒序、分页语义遵循 `PageRequest`/`PageResult` 规范

#### Scenario: 管理员新增或编辑词条

- **WHEN** 管理员调用 `POST /admin/sensitiveWord/saveWord`（`word/level/status`，带 `id` 为编辑）
- **THEN** 保存成功并自动 `reload()`，`level∈{1,2,3}`、`status∈{0,1}` 由 `@Valid` 强制
- **AND** 编辑时改名遇同名存活词条同样被拒

#### Scenario: 新增重复词条被拒

- **WHEN** 新增的 `word` 已存在存活词条（或命中唯一索引）
- **THEN** 返回 `CODE_2704 词条已存在`，不落库不 reload

#### Scenario: 删除不存在的词条

- **WHEN** 调用 `POST /admin/sensitiveWord/deleteWord`，`id` 不存在或已删除
- **THEN** 返回 `CODE_2705 词条不存在`

#### Scenario: 逻辑删除

- **WHEN** 删除一个存活词条
- **THEN** `delete_flag` 置为当前时间戳 ms（不物理删除），并自动 `reload()`

#### Scenario: 非管理员被拦截

- **WHEN** 普通用户调用 `/admin/sensitiveWord/*` 任一端点
- **THEN** 被 `checkAdmin` 拦截，返回 `CODE_404`

---

### Requirement: 批量导入（word-bulk-import）— C2

管理员必须能够以 txt 或 csv 批量导入词条，系统逐行容错且同词不重复入库。

#### Scenario: 导入 txt

- **WHEN** 上传 `.txt`（≤5000 行、≤2MB，一行一词）并指定统一 `level/status`
- **THEN** 逐行解析绑定，返回 `Result<ImportResultVO>`（`success/skipped/failed` 计数），成功行自动进入词库并触发一次 `reload()`

#### Scenario: 导入 csv

- **WHEN** 上传 `.csv`（三列 `word,level,status`，≤5000 行、≤2MB）
- **THEN** 按行取级别与状态，同 txt 返回三态计数

#### Scenario: 重复词条跳过

- **WHEN** 导入行的 `word` 已存在存活词条（含命中唯一索引）
- **THEN** 该行计入 `skipped`，不报错、不整体失败、不产生重复行

#### Scenario: 坏行容错

- **WHEN** 某行为空、`level/status` 越界、或列数不符
- **THEN** 该行计入 `failed`，其余行继续处理

#### Scenario: 文件超限或格式不支持

- **WHEN** 文件 >2MB、>5000 行，或扩展名非 `.txt`/`.csv`
- **THEN** 返回 `CODE_1001 参数非法`，不解析

---

### Requirement: 词库导出与往返（word-export-roundtrip）— C3

管理员必须能够将当前词库导出为 csv，且导出文件可原样导回。

#### Scenario: 导出

- **WHEN** 管理员调用 `GET /admin/sensitiveWord/exportWords`
- **THEN** 返回 `text/csv` 文件流（UTF-8 BOM，列 `word,level,status`），仅含存活词条
- **AND** 对 `=+-@` 开头的值前置单引号，防 Excel 公式注入

#### Scenario: 往返等价

- **WHEN** 将导出文件原样通过导入接口重新上传
- **THEN** 全部行计入 `skipped`（均已存在），词库内容与级别/状态不发生变化

---

### Requirement: 变更即时生效（word-hot-reload）— C4

词库任意写变更后必须即时生效于发送链路过滤。

#### Scenario: 新词即刻拦截

- **WHEN** 管理员新增一条 `level=3` 词条并保存成功
- **THEN** 服务端随即以该词发送消息，被敏感词引擎拦截并返回 `CODE_2701`（不入库不推送）

#### Scenario: level2 替换即刻生效

- **WHEN** 管理员新增一条 `level=2` 词条并保存成功
- **THEN** 随后发送的含该词消息内容被替换为 `***` 后正常送达

#### Scenario: 删除即刻失效

- **WHEN** 管理员删除某词条（无论 `level`）
- **THEN** reload 后该词不再参与过滤：以其发送消息不再触发 2701 / 不再被替换（加载 SQL 限定 `delete_flag=0`）

---

### Requirement: 逻辑删除与唯一性共存（word-softdelete-unique）— C5

已删除词条不得占用 `word` 唯一约束，同一词条必须可反复删除与重新导入。

#### Scenario: 删后重导

- **WHEN** 某存活词条被逻辑删除后，管理员再次导入含同一 `word` 的文件
- **THEN** 该行计入 `success` 正常入库（唯一键 `(word, delete_flag)` 中删除行时间戳互异，不冲突）

#### Scenario: 删后重新新增

- **WHEN** 词条被逻辑删除后，管理员再次通过 `saveWord` 新增同一 `word`
- **THEN** 保存成功，返回 `code=0`

---

## MODIFIED Requirements

### Requirement: 敏感词加载范围（既有 `content-moderation` 敏感词引擎）

**变更前**: 引擎 `@PostConstruct` 加载 `sensitive_word` 中 `status=1` 的全部词条到内存树。

**变更后**: 引擎加载 `sensitive_word` 中 `status=1 AND delete_flag=0` 的词条；管理端写变更后由 `SensitiveWordAdminService` 触发既有 `reload()` 重建。

#### Scenario: 已删词条不进过滤树

- **WHEN** 存在 `delete_flag>0` 的词条记录
- **THEN** 即使其 `status=1`，也不参与任何过滤判定

**变更前（引用原 spec）**: 敏感词过滤仅在启动时加载一次，无在线维护入口。

**变更后**: 词库可由管理端在线维护，且每次写变更后自动热更。

---

## REMOVED Requirements

（无）

---

## 追溯矩阵

| Proposal Capability | Delta Requirement | Task 编号 |
|---------------------|-------------------|-----------|
| C1 筛选与维护 | ADDED: 词库筛选与维护（word-admin-crud） | 阶段一~二、阶段三 |
| C2 批量导入 | ADDED: 批量导入（word-bulk-import） | 阶段二、阶段三 |
| C3 导出往返 | ADDED: 词库导出与往返（word-export-roundtrip） | 阶段二、阶段三 |
| C4 即时生效 | ADDED: 变更即时生效（word-hot-reload） | 阶段二（写后 reload + 加载 SQL） |
| C5 逻辑删除与唯一性 | ADDED: 逻辑删除与唯一性共存（word-softdelete-unique） | 阶段一（migration-007） |
| 既有：敏感词引擎加载 | MODIFIED: 敏感词加载范围 | 阶段二（`delete_flag=0`） |
