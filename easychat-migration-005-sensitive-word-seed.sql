-- ============================================================
-- EasyChat 内容治理：敏感词示例种子
-- ============================================================
-- 说明：
--   本脚本是「可选执行」的示例词库，用于初始化 sensitive_word 表。
--   项目不自动跑此脚本，避免污染既有数据；请在 MySQL 客户端按需手动执行：
--     mysql -uroot -proot -h127.0.0.1 easychat < easychat-migration-005-sensitive-word-seed.sql
--
-- 字段口径（同 easychat-migration-004-im-complete.sql 建表）：
--   level  : 1提醒 2替换 3禁止发送
--   status : 1启用 0停用
--   create_time : 毫秒时间戳，可空（此处省略，取 NULL）
--
-- 生效条件：SensitiveWordService 在应用启动时 @PostConstruct 加载 status=1 的词；
--   修改词库后调用预留的 reload() 即可热更新（本变更不暴露端点，留待管理端）。
-- ============================================================

-- 清空既有示例（如需完全重置可取消下一行注释；默认注释以免误删线上词库）
-- TRUNCATE TABLE sensitive_word;

-- level=3 禁止发送：命中后消息不入库、不推送，返回 CODE_2701
INSERT INTO `sensitive_word` (`word`, `level`, `status`) VALUES
('代开发票',        3, 1),
('博彩投注',        3, 1),
('出售个人账号',    3, 1),
('私下交易担保',    3, 1),
('刷单返利',        3, 1);

-- level=2 替换：命中后内容中该词替换为 *** 后继续入库/推送
INSERT INTO `sensitive_word` (`word`, `level`, `status`) VALUES
('加微信',    2, 1),
('加QQ',      2, 1),
('私聊优惠',  2, 1),
('扫码领福利', 2, 1);
