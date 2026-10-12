# Retro — 接通认证入口的 DTO 字段校验

- Change: `openspec/archive/2026-10-12-account-dto-validation`
- 日期: 2026-10-12

## 做得好的

1. **缺陷先实证再立项**。读到「`@Valid` 0 次使用」时我没有直接下结论，
   而是写了临时探针跑 MockMvc，实测三个请求确实全部 200 直达 Service 层。
   这个证据让「危害是什么」变得可讨论——**危害不是「能建空邮箱账号」（下游另有判空），
   而是「DTO 作者写下的提示永远不会出现」**。若跳过实证，很容易把结论写成
   「严重的账号安全漏洞」，进而误导决策。
2. **异常处理侧本就就绪**。`handleBindException` 早就写好并注释着「@Valid @ModelAttribute」——
   说明这套设计**当初就是想要的**，只是没人接线。补 `@Valid` 是兑现既有设计，
   而非引入新机制。这让「改动是否越界」这个问题自动消解。
3. **删签名的用意被实证**。ADR-002 写「删签名让隐藏调用点逐一编译失败」时，
   这是**推演**；改完签名后 `GlobalOperationAspectTest` 真的抛出 8 处
   `NoSuchMethodException`。推演变成事实。

## 问题

1. **我又一次在 `edit` 时误删代码**。给 `AccountControllerMockMvcTest` 插入新测试时，
   用一段 `oldString` 匹配到测试方法的开头，替换内容里漏了方法体前的一行注释，
   把那行删掉了。靠 `git diff --stat` 发现（文件本应无改动却有 diff）。
   这是本轮**第二次**犯同类错误。
2. **一条用例恒红，我一度以为是实现缺陷**。`classLevelValidatedStillWorks` 始终 200，
   我先怀疑是 `@Validated` 被我改坏了。实际是 `standaloneSetup` 不注册
   `MethodValidationPostProcessor`——**环境限制**。这类「测试环境能力边界」
   与「实现缺陷」混淆，若不查清就会写出「改实现去迁就测试环境」的错误修复。

## 原因

1. `edit` 的 `oldString` 只要不包含整个被替换区块，就可能连带删掉相邻行。
   我对短片段替换的警惕不够——之前靠回读发现，这次靠 `git diff` 发现，
   说明**回读不是可选的纪律而是必需**。
2. 我没有先建立「standaloneSetup 支持什么」的清单，就直接写端到端断言。
   已知 MockMvc 有 `standaloneSetup` 与 `webAppContextSetup` 两种模式，
   却没在写测试前确认本次用的是哪种、它是否支持方法级校验。

## 改进方案

1. **替换短片段前，先确认 oldString 覆盖整个语法单元**（完整方法 / 完整 import 块）。
   若 newString 与 oldString 的首尾行不是对齐的语法边界，一律改用完整区块替换。
   本次教训的具体形态：`oldString` 结尾是方法签名行、`newString` 结尾是同一行，
   看起来「对齐」，实际 newString 少了一行注释。
2. **写 MockMvc 测试前先确认装配模式**。`standaloneSetup` 不启用
   `MethodValidationPostProcessor`、不加载 `Validator` 相关 Bean、
   不执行完整的消息转换器链。凡依赖这些的断言，改用结构断言或
   `webAppContextSetup`。已把这条写入台账 #32 与测试注释，避免下次重踩。
3. **恒红的用例先诊断环境，再质疑实现**。判据：若该断言依赖的是「框架在当前装配模式下
   是否注册了某个 Bean」，先查装配模式；确认环境支持后仍红，才是实现问题。