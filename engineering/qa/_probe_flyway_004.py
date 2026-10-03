"""ADR-005 实测夹具：造一个「已导入基线但缺 004 所加 8 列」的库，
并把改写后的 004 单独放进一个临时迁移目录，供 Flyway 真实执行。

只服务本机实测，不属于仓库测试或门禁。
用法：python engineering/qa/_probe_flyway_004.py setup|verify|again|teardown
"""
import glob
import os
import shutil
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ec_flyway_004"
MIGDIR = r"C:\Users\7even\AppData\Local\Temp\opencode\ec-mig004"

# 004 用存储过程加的 8 列（表, 列）
COLS = [
    ("chat_session_user", "top_type"),
    ("chat_session_user", "no_disturb"),
    ("chat_session_user", "draft"),
    ("user_contact", "remark"),
    ("user_contact", "group_name"),
    ("chat_message", "extra_data"),
    ("chat_message", "at_user_ids"),
    ("chat_message", "duration"),
]


def sql(stmt, db=None):
    args = [MYSQL, "--host=127.0.0.1", "--user=root", "--password=root",
            "--default-character-set=utf8mb4", "-N", "-B", "-e", stmt]
    if db:
        args.append(db)
    return subprocess.run(args, capture_output=True)


def sql1(stmt, db=None):
    return sql(stmt, db).stdout.decode("utf-8", "replace").strip()


def setup():
    sql("DROP DATABASE IF EXISTS %s;" % DB)
    sql("CREATE DATABASE %s DEFAULT CHARSET utf8mb4;" % DB)
    with open("easychat.sql", "rb") as f:
        data = f.read()
    subprocess.run([MYSQL, "--host=127.0.0.1", "--user=root", "--password=root",
                    "--default-character-set=utf8mb4", DB],
                   input=data, capture_output=True)
    # 删掉 004 会加的 8 列，制造「迁移前」状态
    for t, c in COLS:
        r = sql("ALTER TABLE `%s` DROP COLUMN `%s`;" % (t, c), DB)
        if r.returncode != 0:
            print("  drop %s.%s failed: %s" % (t, c, r.stderr.decode("utf-8", "replace")[:120]))
    # 只放 004 一个迁移
    shutil.rmtree(MIGDIR, ignore_errors=True)
    os.makedirs(MIGDIR)
    shutil.copy("easychat-migration-004-im-complete.sql", MIGDIR)
    present = sum(1 for _, c in COLS
                  if sql1("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE "
                          "TABLE_SCHEMA='%s' AND TABLE_NAME='%s' AND COLUMN_NAME='%s';"
                          % (DB, t, c)) == "1")
    print("fixture ready. 8 列中当前存在 %d 个（预期 0）" % present)
    print("migdir:", MIGDIR)


def verify():
    present = [t + "." + c for t, c in COLS
               if sql1("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE "
                       "TABLE_SCHEMA='%s' AND TABLE_NAME='%s' AND COLUMN_NAME='%s';"
                       % (DB, t, c)) == "1"]
    print("8 列中已存在 %d 个: %s" % (len(present), present))
    hist = sql1("SELECT version, description, success FROM %s.flyway_schema_history "
                "ORDER BY installed_rank;" % DB)
    print("history:", hist.replace("\n", " | ") or "(空)")


def again():
    """幂等验证：把 004 再手工跑两遍，不应报错"""
    p = os.path.join(MIGDIR, "easychat-migration-004-im-complete.sql")
    data = open(p, "rb").read()
    for i in (1, 2):
        r = subprocess.run([MYSQL, "--host=127.0.0.1", "--user=root", "--password=root",
                            "--default-character-set=utf8mb4", DB],
                           input=data, capture_output=True)
        print("第 %d 次执行 rc=%d %s" % (i, r.returncode,
              r.stderr.decode("utf-8", "replace")[:160]))


def teardown():
    sql("DROP DATABASE IF EXISTS %s;" % DB)
    shutil.rmtree(MIGDIR, ignore_errors=True)
    print("cleaned")


if __name__ == "__main__":
    {"setup": setup, "verify": verify, "again": again, "teardown": teardown}[sys.argv[1]]()