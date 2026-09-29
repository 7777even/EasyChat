# Retro — 通话记录管理端列表查询（call-log-admin）

- 日期: 2026-09-29
- 效率等级: L3

## 做得好

- [TDD] 冒烟断言先行（先写失败断言再实现），活体跑第一轮就抓出 `CallLogQuery` 缺 getter/setter 的 500——若放到收尾阶段才发现会拖慢闭环。
- 权限断言没有照抄 09-26 先例的 `901/404`，而是回溯 `bdf854f` 与 `GlobalOperationAspect`/`inferHttpStatus` 源码确认现行契约（2001/1003 + HTTP 401/400），断言与代码事实一致。
- fixture 设计覆盖了边界维度：默认 15 条分页、`end_time` NULL 计算列、40 天前行测时间窗外、注销用户测 JOIN、跑完全量清理且真实数据基线不变。

## 问题

- `CallLogQuery` 新增 `startTime`/`endTime` 字段时漏写 getter/setter：`mvn compile` 通过但 OGNL 运行期 `NoSuchPropertyException`，接口 500。
- 冒烟预期值两度返工：先按旧码 `901/404` 写错，再误以为非管理员是 HTTP 404（实际 404+1003 只属死路由分支，`BusinessException(1003)` 走兜底映射为 400）。
- fixture `user_id` 超表长（13 > `user_info.user_id` 12）首跑即插库失败。
- Windows 环境两坑：PowerShell 直连 mysql 参数 `-h127.0.0.1` 被拆坏、管道喂 SQL 把 UTF-8 注释转 `????` 导致 1064。

## 原因

- 只把「加字段」当编译级改动，未意识到 MyBatis OGNL 与 Spring 绑定依赖 JavaBean 访问器，编译器不校验这一层。
- 断言参照的是历史 QA 终端输出（跑于 `bdf854f` 重构前），没有以当前 `ResponseCodeEnum` + 异常处理器源码为唯一事实源。
- 造 fixture 前未先查 `information_schema` 列长度。
- PowerShell 对原生命令的参数与编码处理与 Python subprocess（既有冒烟底层）行为不一致，脚本可移植但手工 shell 命令不可直接照搬。

## 改进方案

- 查询对象加字段的标准动作补一步：写完即确认 getter/setter 成对存在（或活体冒烟前置一个「字段回显」探针），编译通过≠运行期可绑定。
- 写权限/错误码断言前，先读 `ResponseCodeEnum` 与 `AGlobalExceptionHandlerController` 的映射表，历史 QA 输出只作结构参考不作数值参考。
- 造库 fixture 固定流程：先 `information_schema.columns` 查长度 → 再插数据。
- 数据库手工操作统一走 `cmd /c ... < file` + `--default-character-set=utf8mb4`（原始字节直通），避免 PowerShell 管道转码。
