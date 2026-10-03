r"""统计变异脚本输出里的 捕获/漏网 计数（绕开 PowerShell 的 GBK 控制台乱码）。

用法：node scripts/verify/mutation_migration_flyway.cjs > "<tmp>\mv.txt"
      python engineering/qa/_count_mut.py "<tmp>\mv.txt"
"""
import io
import re
import sys

CASES = "用例数"
CAP = "[捕获]"
LEAK = "[漏网]"


def main():
    path = sys.argv[1]
    raw = open(path, "rb").read()
    # PowerShell 的 > 重定向写 UTF-16LE；Set-Content -Encoding utf8 写 BOM+UTF-8
    for enc in ("utf-8-sig", "utf-16", "utf-8"):
        try:
            s = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit("无法解码：" + path)

    m = re.search(CASES + r"[:：]\s*(\d+)", s)
    print("用例数 = %s" % (m.group(1) if m else "?"))
    print("捕获 = %d，漏网 = %d" % (s.count(CAP), s.count(LEAK)))
    for line in s.replace("\r\n", "\n").split("\n"):
        if "结论" in line or "判别力" in line or LEAK in line:
            print(line)


if __name__ == "__main__":
    main()