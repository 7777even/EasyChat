"""T2.3 实测夹具：在**库副本**上制造漂移，验证 preflight 的三条路径。

刻意不动真实开发库 easychat —— 造漂移要用 DROP COLUMN，污染开发库代价太高。
用法：python engineering/qa/_probe_preflight.py setup|clean
"""
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ec_preflight_probe"


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
    r = subprocess.run([MYSQL, "--host=127.0.0.1", "--user=root", "--password=root",
                        "--default-character-set=utf8mb4", DB],
                       input=data, capture_output=True)
    print("副本已建，rc=%d，表数=%s" % (r.returncode, sql1(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%s';" % DB)))
    # 制造漂移：删一列（模拟「改了基线但没迁移」的存量库）
    d = sql("ALTER TABLE `user_contact` DROP COLUMN `remark`;", DB)
    print("DROP user_contact.remark rc=%d" % d.returncode)
    print("（现在该副本比基线少 1 列，preflight 应拒绝）")


def clean():
    sql("DROP DATABASE IF EXISTS %s;" % DB)
    print("副本已清理")


if __name__ == "__main__":
    {"setup": setup, "clean": clean}[sys.argv[1]]()