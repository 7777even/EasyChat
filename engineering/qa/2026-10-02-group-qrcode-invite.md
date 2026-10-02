# QA 报告 — 群二维码与群邀请（前端补齐 + 后端桩修复）

- 日期: 2026-10-02
- 关联 Change: openspec/changes/2026-09-30-group-qrcode-invite
- 说明: 本变更 2026-09-30 曾归档，2026-10-01 复核发现前端为零而移回；本次补齐前端、修复后端桩并重新归档。

## 范围

| 端 | 改动 |
|----|------|
| 后端 | `RedisComponet.getGroupIdByToken` 桩实现 → 反查索引；`GroupQrCodeServiceImpl` / `GroupInviteServiceImpl` 各自的私有桩统一委托；`Constants` 加 2 个反查 key |
| 前端 | `GroupDetail.vue` 加「群二维码」「群邀请链接」按钮（仅群主/管理员）+ 两个弹窗 |
| 依赖 | 新增前端 `qrcode` 包（纯 JS、无原生依赖），**已经人工确认** |

## 本次修复的后端缺陷（重要）

`getGroupIdByToken` 共有**三处桩**，全部 `return null`：
1. `RedisComponet.getGroupIdByToken`
2. `GroupQrCodeServiceImpl` 的私有 `getGroupIdByToken`
3. `GroupInviteServiceImpl` 的私有 `getGroupIdByToken`

即 `/qrCode/join` 与 `/invite/join` **永远失败**（"二维码已过期或无效" / "邀请链接已过期或无效"），即使前端补齐也无法入群。

修复：生成 token 时同步写反查索引（`easychat:group:qrcode:token:{token}` / `easychat:group:invite:token:{token}` → groupId，与正向同 TTL 7 天），三处统一走 `RedisComponet.getGroupIdByToken`。

## 验收口径

1. 4 个孤路由全部接线。
2. 活体：建群 → 生成 → 非成员加入 → 成功；重复加入/无效 token → 正确拒绝。
3. 前端 0 新增 lint error、`npm run build` 通过；后端 `mvn test` 全绿。

## 实际执行命令与用例数

| 命令 | 结果 |
|------|------|
| `node scripts/check-api-contract.mjs` | 修复前 7 个孤路由 → **群码/邀请 4 个已消除**，剩 0 个（favorite 3 个由收藏变更消除） |
| `npx eslint src/renderer/src/views/contact/GroupDetail.vue` | **0 error** |
| `npm run build` | built in 13.44s |
| `mvn -B -o compile` | BUILD SUCCESS |
| `mvn -B -o test` | Tests run: **120**, Failures: 0 |
| `node scripts/check-openspec-hygiene.mjs` | 通过 |

### 活体证据（本机 MySQL 3306 / Redis 6379 / 后端 5050）

```
owner login: 200 0
--- 1. 生成群二维码 ---      generate: 200 0 success, token=94fbd91ca3904ad28cdb...
--- 2. 非成员扫码加入 ---    join by qrCode: 200 0 success   ← 修复前必失败
--- 3. 生成群邀请链接 ---    generate: 200 0 success
--- 4. 注册新用户 ---        register: 200 0 success（同时验证注册链路）
--- 5. 新用户登录 ---        200 0
--- 6. 经邀请链接加入 ---    join by invite: 200 0 success   ← 修复前必失败
--- 7. 重复加入 ---          re-join: 400 1001 您已经是该群成员
--- 8. 无效 token ---         bad token: 400 1001 二维码已过期或无效
```

测试数据已清理（QA 群、会话、测试用户均已删除）。

## 未运行项

- **真机扫码**：QR 图渲染用 `qrcode` 包生成 dataURL，未用真机相机验证可扫性（算法为标准 QR，风险低）。
- **GUI 交互**：群详情页按钮 → 弹窗 → 复制的点击链路待本机手动验证。

## 结论

**通过**。4 个孤路由全部接线，后端桩修复并经活体验证（join 由永远失败变为成功），重复加入与无效 token 正确拒绝。GUI 与真机扫码待手动验证。

遗留：token 为裸 UUID，未编码进 URL（前端直接展示 token 文本）；如需「扫码即带参跳转」需另开变更。
