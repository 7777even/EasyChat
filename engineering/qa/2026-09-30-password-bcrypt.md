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
