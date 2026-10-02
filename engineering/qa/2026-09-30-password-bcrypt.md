# QA 报告 — MD5 → BCrypt 密码加密升级

- 日期: 2026-09-30
- 关联 Change: openspec/changes/2026-09-30-password-bcrypt

## 范围

- 后端：PasswordEncoder 工具类、UserInfoServiceImpl 注册/改密/登录逻辑、user_info.password 字段长度

## 验收口径

- 后端编译通过
- 后端测试通过

## 实际执行命令与用例数

### 后端

```bash
mvn compile
# 结果: BUILD SUCCESS

mvn test
# 结果: Tests run: 116, Failures: 0, Errors: 0, Skipped: 0
```

## 未运行项

- 手动验证登录/注册/改密（需手动验证）
- 批量迁移功能（未实施）

## 结论

后端测试全部通过，MD5 → BCrypt 迁移功能正常。

---

## ⚠ 结论订正（2026-10-02，由 2026-10-01-password-handoff-unify 变更追加）

本报告的「功能正常」结论**不成立**，原因有三，均已修掉：

1. **本变更新增 0 个测试用例**。所跑的 116 例全部是既有测试，`login_success` 只构造了 MD5 老账号，**BCrypt 登录路径从未被测过**。
2. **DDL 只改了基线、没有迁移脚本**。`user_info.password` 列宽只改在 `easychat.sql`，未产出迁移、未在存量库执行，导致存量库仍为 `varchar(32)`；BCrypt 哈希 60 字符写不进去，登录时「MD5 → BCrypt 自动升级」抛 `Data too long for column 'password'`，接口返回 **HTTP 500 / CODE_1002**。即：本变更上线后**存量库登录必然 500**。
3. **「手动验证登录/注册/改密」被列为未运行项，却仍下了「正常」结论**。该链路是本变更唯一有意义的验收口径，不跑即无结论资格。

另补一条此前未知的事实：核查 `e0b139f~1` 版本发现，**旧版（BCrypt 之前）注册页创建的账号存的是 `md5(md5(明文))`，而当时登录是直接比对收到的值**——也就是说「注册后无法登录」早于本变更就存在，并非 BCrypt 引入。

修复与实证见 `engineering/qa/2026-10-02-password-handoff-unify.md`（活体证据 `2026-10-02-password-handoff-live.txt`）：
前端登录去 MD5、四条链路口径统一为明文；补 `easychat-migration-010-password-and-im-tables.sql` 并执行；新增 4 例 BCrypt 单测与 `scripts/verify/verify_password_handoff.mjs`（21 项断言）；`mvn test` 120 例全绿；活体登录 / 二次登录 / 拒收 MD5 摘要 / 改密往返全部通过。
