-- ============================================================
-- 跨实例通话冒烟 · 测试数据种子
--   配套脚本：scripts/smoke/multi-instance-*.mjs
--
-- 用法：
--   mysql -uroot -proot -D easychat --default-character-set=utf8mb4 < scripts/smoke/seed-test-users.sql
--
-- 幂等：可整段重跑（先删后建）。只新增 `U900000000xx` 前缀的账号与好友关系，
--       **不触碰任何既有业务数据**。测完想清干净：
--   DELETE FROM user_contact WHERE user_id LIKE 'U9000000000%' OR contact_id LIKE 'U9000000000%';
--   DELETE FROM user_info     WHERE user_id LIKE 'U9000000000%';
--
-- 口令：明文 Test12345（满足 REGEX_PASSWORD），此处存 MD5 ——
--       走 UserInfoServiceImpl 既有的 MD5 双验证路径，登录成功即自动升级为 BCrypt。
--       （对应前端 REGEX_PASSWORD = 长度 8-18、字母+数字）
--
-- 8 个账号 = 4 对好友，供「并发隔离冒烟」使用：
--   (U90000000001, U90000000002)  (U90000000003, U90000000004)
--   (U90000000005, U90000000006)  (U90000000007, U90000000008)
-- 每对的「发起方」连实例 A、「被叫方」连实例 B → 4 路全部跨实例。
-- ============================================================
DELETE FROM user_contact WHERE user_id IN ('U90000000001','U90000000002','U90000000003','U90000000004',
                                            'U90000000005','U90000000006','U90000000007','U90000000008')
                            OR contact_id IN ('U90000000001','U90000000002','U90000000003','U90000000004',
                                              'U90000000005','U90000000006','U90000000007','U90000000008');
DELETE FROM user_info     WHERE user_id IN ('U90000000001','U90000000002','U90000000003','U90000000004',
                                            'U90000000005','U90000000006','U90000000007','U90000000008');

INSERT INTO user_info (user_id, email, nick_name, password, status, join_type, sex,
                       create_time, moment_visibility, online_status_visible)
VALUES
 ('U90000000001','mi-a1@easychat.test','MI-A1','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000002','mi-b1@easychat.test','MI-B1','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000003','mi-a2@easychat.test','MI-A2','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000004','mi-b2@easychat.test','MI-B2','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000005','mi-a3@easychat.test','MI-A3','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000006','mi-b3@easychat.test','MI-B3','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000007','mi-a4@easychat.test','MI-A4','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1),
 ('U90000000008','mi-b4@easychat.test','MI-B4','662af1cd1976f09a9f8cecc868ccc0a2',1,0,1,NOW(),0,1);

-- 双向好友（callType=1 单聊呼叫要求 user_contact 存在）
INSERT INTO user_contact (user_id, contact_id, contact_type, create_time, status, role, remark)
VALUES
 ('U90000000001','U90000000002',0,NOW(),1,2,''),
 ('U90000000002','U90000000001',0,NOW(),1,2,''),
 ('U90000000003','U90000000004',0,NOW(),1,2,''),
 ('U90000000004','U90000000003',0,NOW(),1,2,''),
 ('U90000000005','U90000000006',0,NOW(),1,2,''),
 ('U90000000006','U90000000005',0,NOW(),1,2,''),
 ('U90000000007','U90000000008',0,NOW(),1,2,''),
 ('U90000000008','U90000000007',0,NOW(),1,2,'');
