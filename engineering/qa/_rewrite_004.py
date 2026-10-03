"""一次性工具：从 git HEAD 的原文生成 004 的 PREPARE/EXECUTE 版本。

目的：保证「未触及的部分逐字节不变」。
此前手抄版本把 file_type/reason/status 的 COMMENT 从 0-based 误改成 1-based，
属未授权改动。本脚本以 HEAD 原文为唯一来源，只替换两处：
  ① 存储过程定义块（含 DELIMITER）
  ② CALL ec_add_column(...) 调用 → SET @ddl + PREPARE/EXECUTE/DEALLOCATE
用法：python engineering/qa/_rewrite_004.py
"""
import io
import re
import subprocess

PATH = "easychat-migration-004-im-complete.sql"
Q = "'"

NOTICE = (
    "-- =====================================================================\n"
    "-- \u26a0\ufe0f 2026-10-04 \u91cd\u5199\uff1a\u52a0\u5217\u65b9\u5f0f\u7531\u300c\u5b58\u50a8\u8fc7\u7a0b + DELIMITER\u300d\u6539\u4e3a\n"
    "--    \u300cSET @ddl=(SELECT IF(\u5217\u5b58\u5728,'SELECT 1','ALTER ...')) + PREPARE/EXECUTE\u300d\u3002\n"
    "--    \u539f\u56e0\uff1aDELIMITER \u662f mysql \u5ba2\u6237\u7aef\u6307\u4ee4\u800c\u975e SQL\uff0cFlyway \u7684 MySQL \u89e3\u6790\u5668\u4e0d\u652f\u6301\u5b83\n"
    "--    \uff08\u89c1 openspec/changes/2026-10-04-flyway-migration-automation\uff09\u3002\n"
    "--    \u6539\u540e\u884c\u4e3a\u7b49\u4ef7\uff08\u4ecd\u662f\u300c\u5217\u4e0d\u5b58\u5728\u624d\u52a0\u300d\uff09\uff0c\u4e14\u4e24\u6761\u6267\u884c\u8def\u5f84\u90fd\u5b89\u5168\uff1a\n"
    "--    Flyway \u81ea\u52a8\u6267\u884c \u4e0e \u4eba\u5de5\u91cd\u8dd1\u3002\n"
    "--\n"
    "--    \u26a0\ufe0f MySQL 5.7 \u4e0d\u652f\u6301 `ADD COLUMN IF NOT EXISTS`\uff088.0.29+ \u624d\u6709\uff09\uff0c\n"
    "--       \u6545\u5fc5\u987b\u501f PREPARE/EXECUTE \u505a\u6761\u4ef6\u5224\u65ad\u2014\u2014\u4e5f\u8fd9\u662f\u4fdd\u7559\u5e42\u7b49\u6027\u7684\u552f\u4e00\u9014\u5f84\u3002\n"
)


def main():
    orig = subprocess.run(
        ["git", "show", "HEAD:" + PATH], capture_output=True, check=True
    ).stdout.decode("utf-8")

    # ① 摘掉存储过程定义块
    start = orig.index("-- ---------- \u901a\u7528\uff1a\u5b89\u5168\u52a0\u5217\u5b58\u50a8\u8fc7\u7a0b ----------")
    end = orig.index("-- ---------- 1. chat_session_user")
    s = orig[:start] + orig[end:]

    # ② 提取 CALL，生成 PREPARE 块
    call_re = re.compile(
        r"CALL ec_add_column\((?P<q>%s)(?P<table>[^%s]+)(?P=q),\s*"
        r"(?P=q)(?P<col>[^%s]+)(?P=q),\s*'(?P<define>.*?)'\);" % (Q, Q, Q),
        re.S,
    )
    calls = list(call_re.finditer(s))
    print("CALL \u6570\u91cf:", len(calls))

    def block(m):
        table = m.group("table")
        col = m.group("col")
        # ⚠ define 捕获自原文，而原文里的 COMMENT 引号**已是 SQL 转义形态**（''）。
        #    因此这里直接嵌入新字符串字面量即可，**绝不能再转义一次**——
        #    重复转义会产生 4 个连续引号，报 1064 语法错（2026-10-04 实测踩过）。
        ddl = "ALTER TABLE `%s` ADD COLUMN `%s` %s" % (table, col, m.group("define"))
        return (
            "SET @ddl = (SELECT IF(COUNT(*) > 0, 'SELECT 1', '%s')\n"
            "FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '%s' AND COLUMN_NAME = '%s');\n"
            "PREPARE stmt FROM @ddl;\n"
            "EXECUTE stmt;\n"
            "DEALLOCATE PREPARE stmt;\n" % (ddl, table, col)
        )

    s = call_re.sub(block, s)
    s = s.replace("DROP PROCEDURE IF EXISTS `ec_add_column`;\n\n", "")

    # ③ 顶部换说明块（在首个 ---------- 标题之前插入）
    first_sec = s.index("-- ---------- 1. chat_session_user")
    s = s[:first_sec] + NOTICE + s[first_sec:]

    io.open(PATH, "w", encoding="utf-8", newline="\n").write(s)
    print("written", PATH)


if __name__ == "__main__":
    main()