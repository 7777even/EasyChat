"""T0.3/T0.4 实测辅助：创建/删除 Flyway 实测用的一次性库。
仅本机开发验证使用，不属于仓库测试或门禁。
"""
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
DB = "ec_flyway_probe"


def sql(stmt):
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--user=root", "--password=root",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", stmt],
        capture_output=True)


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "reset"
    if action == "reset":
        sql("DROP DATABASE IF EXISTS %s;" % DB)
        sql("CREATE DATABASE %s DEFAULT CHARSET utf8mb4;" % DB)
        r = sql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
                "WHERE SCHEMA_NAME='%s';" % DB)
        print("probe db ready:", r.stdout.decode("utf-8", "replace").strip())
    elif action == "drop":
        sql("DROP DATABASE IF EXISTS %s;" % DB)
        print("probe db dropped")
    elif action == "tables":
        r = sql("SELECT COUNT(*) FROM information_schema.tables "
                "WHERE table_schema='%s';" % DB)
        print("table count:", r.stdout.decode("utf-8", "replace").strip())
    elif action == "history":
        r = sql("SELECT installed_rank, version, description, success "
                "FROM %s.flyway_schema_history ORDER BY installed_rank;" % DB)
        print(r.stdout.decode("utf-8", "replace").strip() or "(no history)")
    elif action == "real":
        # 真实开发库 easychat 的 Flyway 记账状况（T3.2 / T3.4 验证用）
        db = "easychat"
        exists = sql("SELECT COUNT(*) FROM information_schema.tables "
                     "WHERE table_schema='%s' AND table_name='flyway_schema_history';" % db)
        print("flyway_schema_history 存在数 =", exists.stdout.decode("utf-8", "replace").strip())
        r = sql("SELECT installed_rank, version, description, type, success "
                "FROM %s.flyway_schema_history ORDER BY installed_rank;" % db)
        print("history:")
        print(r.stdout.decode("utf-8", "replace").strip() or "(空)")
        t = sql("SELECT COUNT(*) FROM information_schema.tables "
                "WHERE table_schema='%s' AND table_type='BASE TABLE';" % db)
        print("业务表总数（应为 26，不因 flyway 而增加） =",
              t.stdout.decode("utf-8", "replace").strip())


if __name__ == "__main__":
    main()