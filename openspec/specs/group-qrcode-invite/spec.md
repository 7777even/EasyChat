# Spec — 群二维码与群邀请

## ADDED Requirements

### Requirement: 生成群二维码

群主/管理员生成群二维码，供他人扫码加入。

#### Scenario: 生成群二维码

- **WHEN** 群主或管理员在群详情点击「群二维码」
- **THEN** 服务端生成 token 并写入 Redis（正向 `groupId→token` + 反查 `token→groupId`，TTL 7 天）
- **AND** 前端渲染为二维码图片，附 token 文本与复制按钮

#### Scenario: 仅群主/管理员可生成

- **WHEN** 普通成员点击生成
- **THEN** 服务端返回"只有群主/管理员可以生成群二维码"
- **AND** 前端不展示该按钮

---

### Requirement: 通过二维码加入群组

外部用户通过二维码 token 加入群组。

#### Scenario: 扫码加入

- **WHEN** 已登录用户提交有效二维码 token
- **THEN** 服务端按反查索引解析出 groupId 并将该用户加为群成员
- **AND** 返回成功

#### Scenario: 无效或过期 token

- **WHEN** token 不存在或已过期
- **THEN** 返回"二维码已过期或无效"
- **AND** 不产生任何成员记录

#### Scenario: 重复加入被拒

- **WHEN** 用户已是该群成员再次加入
- **THEN** 返回"您已经是该群成员"

---

### Requirement: 生成群邀请链接

群主/管理员生成群邀请链接。

#### Scenario: 生成邀请链接

- **WHEN** 群主或管理员点击「群邀请链接」
- **THEN** 服务端生成 token 并写入 Redis（正向 + 反查索引，TTL 7 天）
- **AND** 前端展示 token 与复制按钮

---

### Requirement: 通过邀请链接加入群组

外部用户通过邀请链接 token 加入群组。

#### Scenario: 经邀请链接加入

- **WHEN** 已登录用户提交有效邀请 token
- **THEN** 服务端按反查索引解析出 groupId 并加为群成员
- **AND** 返回成功

#### Scenario: 无效或过期 token

- **WHEN** token 不存在或已过期
- **THEN** 返回"邀请链接已过期或无效"

---

### Requirement: 反查索引与正向映射一致

二维码/邀请链接的反查索引必须在生成时同步写入。

#### Scenario: 生成即写反查

- **WHEN** 生成二维码或邀请链接 token
- **THEN** 同时写入 `easychat:group:qrcode:token:{token}` 或 `easychat:group:invite:token:{token}` → groupId
- **AND** 反查索引与正向映射 TTL 相同（7 天）

#### Scenario: 反查不依赖遍历

- **WHEN** 按 token 查 groupId
- **THEN** 直接读反查索引，不遍历全量群组

---

## MODIFIED Requirements

无。

---

## REMOVED Requirements

无。
