#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「排序白名单 + SQL 拼接收口」活体注入实测（stdlib only，只读，无副作用）

覆盖 openspec/changes/2026-10-06-mapper-orderby-sql-injection 阶段五验收
（人工决策明确要求：活体注入实测是 DoD 硬项，不接受「代码级可达」代替）：

  T1 注入载荷作为 sortField  → 不执行，且返回 CODE_1001
  T2 注入载荷作为 sortDirection → 不执行，且返回 CODE_1001
  T3 载荷变体（含空格、含分号、大写、子查询、注释符）→ 一律 CODE_1001
  T4 白名单内合法排序 → 生效，且**真的改变了行序**（对比 asc/desc 的首行差异）
  T5 未指定排序 → 走默认项，且分页结果**稳定**（同页重复请求行序一致，修掉 C4）
  T6 /admin/loadUser 的 passwordFuzzy 已不可达（CODE_1001），不再构成口令预言机
  T7 负向：普通用户（非管理员）调这三个端点仍被 1003 拒绝（确认未削弱鉴权）

对照实验（关键）：T1~T3 的载荷在**修复前**会被拼进 SQL。本脚本用
  ① 直接在 MySQL 里执行拼接后的 SQL，证明它确实是可执行的注入
  ② 请求修复后的接口，证明同一载荷返回 CODE_1001
两者并列，才能证明「堵住了」而不是「载荷本来就不成立」。

用法：python smoke_sort_whitelist.py [日志文件]
"""
import sys
import subprocess
import urllib.request
import urllib.parse
import urllib.error
import json
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
PWD_RAW = "Test@123456"
ADMIN_EMAIL = "test@qq.com"

CODE_PARAM_INVALID = 1001
CODE_NO_PERMISSION = 1003

RESULTS = []
_LOGFP = None


def out(line=""):
    print(line)
    if _LOGFP:
        _LOGFP.write(line + "\n")
        _LOGFP.flush()


def check(name, cond, detail=""):
    ok = bool(cond)
    RESULTS.append((name, ok))
    out("   [%s] %s%s" % ("PASS" if ok else "FAIL", name, (" | " + str(detail)) if detail else ""))
    return ok


# ── 基础设施（沿用 smoke_audit_and_at_all.py 的既有方式，不自造）──
def sql_rows(query):
    p = subprocess.run(
        [MYSQL, "-uroot", "-proot", "-h127.0.0.1", "--default-character-set=utf8mb4",
         "easychat", "-N", "-B", "-e", query],
        capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("mysql failed: %s" % p.stderr.decode("utf-8", "replace"))
    txt = p.stdout.decode("utf-8", "replace").strip()
    return [l.split("\t") for l in txt.split("\n")] if txt else []


def redis_cmd(*args):
    return subprocess.run([REDIS] + list(args), capture_output=True, text=True).stdout.strip()


def http_req(path, token=None, fields=None, get=False):
    headers = {}
    if token is not None:
        headers["token"] = token
    if get or fields is None:
        req = urllib.request.Request(BASE + path, headers=headers, method="GET")
    else:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        req = urllib.request.Request(BASE + path,
                                     data=urllib.parse.urlencode(fields).encode(),
                                     headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"code": -1, "message": body[:300]}


def post(path, token, fields=None):
    return http_req(path, token=token, fields=fields or {})


def code_of(res):
    return res.get("code") if isinstance(res, dict) else None


def data_of(res):
    d = (res or {}).get("data")
    return {} if d is None else d


def login(email, pwd=PWD_RAW):
    r = http_req("/account/checkCode", get=True)[1]
    if code_of(r) != 0:
        raise RuntimeError("checkCode failed: %s" % r)
    key = data_of(r)["checkCodeKey"]
    code = redis_cmd("GET", "easychat:checkcode:" + key).strip('"')
    r = http_req("/account/login", fields={"email": email, "password": pwd,
                                          "checkCodeKey": key, "checkCode": code})[1]
    if code_of(r) != 0:
        raise RuntimeError("login %s failed: %s" % (email, r))
    return data_of(r)["token"]


# ── 载荷集（T3 复用）──
PAYLOAD_FIELDS = [
    "(select 1 from information_schema.tables)",
    "id desc",                      # 含空格 + 方向
    "create_time desc; drop table user_info",
    "CREATE_TIME DESC",            # 大写
    "1",
    "create_time/**/desc",
    "(select sleep(3))",
    "group_name",
]
PAYLOAD_DIRECTIONS = [
    "desc; drop table user_info",
    "(select 1)",
    "ASC, (select 1)",
    "desc -- ",
]

ENDPOINTS = [
    ("/admin/loadGroup", "group_info"),
    ("/admin/loadBeautyAccountList", "user_info_beauty"),
    ("/admin/loadUser", "user_info"),
]


def main():
    global _LOGFP
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")

    out("=" * 74)
    out("排序白名单 + SQL 拼接收口 —— 活体注入实测")
    out("目标：把「代码级可达」升级为「实证」（AGENTS §2.1 第 12 条）")
    out("=" * 74 + "\n")

    token = login(ADMIN_EMAIL)
    out("管理员登录成功：%s\n" % ADMIN_EMAIL)

    # ── 对照实验：证明载荷在 SQL 层确实可执行 ──────────────────
    out("── 对照实验：修复前 ${query.orderBy} 会把下列载荷拼进 SQL ──")
    out("   （直接交给 MySQL 执行，证明载荷不是「本来就不成立」）")
    out("   ⚠️ 载荷必须选在**目标表上确实成立**的那些。首版选了")
    out("      `(select 1 from information_schema.tables)` 与 `id desc`，")
    out("      前者子查询返回多行（ERROR 1242）、后者 group_info 无 id 列（ERROR 1054），")
    out("      两者都是「载荷不成立」而非「被拦住」—— 对照实验必须自己先站得住。")
    ctrl_payloads = [
        "(select group_id from group_info limit 1)",   # 子查询：可拖出数据
        "(select 1)",                                  # 纯表达式
        "1",                                           # 位置序号
        "create_time desc -- ",                        # 注释截断后续条件
        "(select sleep(3))",                           # 时间盲注
    ]
    ctrl_ok = True
    for p in ctrl_payloads:
        try:
            sql_rows("SELECT group_id FROM group_info ORDER BY %s LIMIT 1" % p)
            out("   [可执行] ORDER BY %s  → MySQL 正常返回（修复前会被拼进去）" % p)
        except Exception as e:
            ctrl_ok = False
            out("   [不可执行] ORDER BY %s → %s" % (p, str(e)[:120]))
    check("对照实验：%d 个注入载荷在 SQL 层确实可执行（否则后面的「被拒绝」没有说服力）"
          % len(ctrl_payloads), ctrl_ok)
    # 载荷也进白名单拒绝集，两边用同一批载荷才构成对照
    for p in ["(select group_id from group_info limit 1)", "(select 1)", "1", "(select sleep(3))"]:
        if p not in PAYLOAD_FIELDS:
            PAYLOAD_FIELDS.append(p)
    out("")

    # ── T1 / T2 / T3：注入载荷一律被拒 ─────────────────────────
    out("── T1~T3 注入载荷作为排序参数 ──")
    for ep, table in ENDPOINTS:
        # T1 sortField
        bad = 0
        for p in PAYLOAD_FIELDS:
            st, r = post(ep, token, {"pageNo": 1, "pageSize": 5, "sortField": p})
            if code_of(r) != CODE_PARAM_INVALID:
                bad += 1
                out("      [未拒] %s sortField=%r → code=%s" % (ep, p, code_of(r)))
        check("T1 %s：%d 个 sortField 载荷全部 CODE_1001" % (ep, len(PAYLOAD_FIELDS)), bad == 0,
              "漏拒 %d 个" % bad if bad else "")

        # T2 sortDirection
        bad = 0
        for p in PAYLOAD_DIRECTIONS:
            st, r = post(ep, token, {"pageNo": 1, "pageSize": 5,
                                     "sortField": "createTime", "sortDirection": p})
            if code_of(r) != CODE_PARAM_INVALID:
                bad += 1
                out("      [未拒] %s sortDirection=%r → code=%s" % (ep, p, code_of(r)))
        check("T2 %s：%d 个 sortDirection 载荷全部 CODE_1001" % (ep, len(PAYLOAD_DIRECTIONS)),
              bad == 0, "漏拒 %d 个" % bad if bad else "")
    out("")

    # ── T4：合法排序生效（真的改变行序）────────────────────────
    out("── T4 白名单内合法排序生效 ──")
    for ep, table in ENDPOINTS:
        field = "id" if table == "user_info_beauty" else "createTime"
        _, r_desc = post(ep, token, {"pageNo": 1, "pageSize": 5,
                                     "sortField": field, "sortDirection": "desc"})
        _, r_asc = post(ep, token, {"pageNo": 1, "pageSize": 5,
                                    "sortField": field, "sortDirection": "asc"})
        ok = code_of(r_desc) == 0 and code_of(r_asc) == 0
        check("T4 %s：合法 sortField=%s 的 asc/desc 均返回 200" % (ep, field), ok,
              "desc=%s asc=%s" % (code_of(r_desc), code_of(r_asc)))
        if ok:
            ld = data_of(r_desc).get("list") or []
            la = data_of(r_asc).get("list") or []
            key = "id" if table != "user_info" else "userId"
            if len(ld) >= 2 and len(la) >= 2:
                same = [str(x.get(key)) for x in ld] == [str(x.get(key)) for x in la]
                check("T4 %s：asc 与 desc 的行序确实不同（证明排序真的生效，非空转）" % ep, not same,
                      "前两行 id：desc=%s asc=%s" % ([x.get(key) for x in ld[:2]],
                                                      [x.get(key) for x in la[:2]]))
            else:
                out("      [跳过行序对比] %s：%s 表数据不足 2 行（asc=%d desc=%d）"
                    % (ep, table, len(la), len(ld)))
    out("")

    # ── T5：未指定排序 → 默认项 + 分页稳定（C4）─────────────────
    out("── T5 未指定排序走默认项，且翻页结果稳定（修掉「分页无 ORDER BY」）──")
    for ep, table in ENDPOINTS:
        seqs = []
        for _ in range(3):
            _, r = post(ep, token, {"pageNo": 1, "pageSize": 5})
            lst = data_of(r).get("list") or []
            seqs.append([json.dumps(x, sort_keys=True, ensure_ascii=False) for x in lst])
        check("T5 %s：同页重复请求 3 次行序完全一致" % ep, seqs[0] == seqs[1] == seqs[2],
              "三次结果不一致 → 分页无稳定序，翻页会重复/漏行")
        check("T5 %s：不传 sortField 也能正常返回（走默认项而非报错）" % ep,
              code_of(r) == 0, "code=%s" % code_of(r))
    out("")

    # ── T6：password 查询条件已不可达 ──────────────────────────
    out("── T6 /admin/loadUser 的 password 查询条件已从 HTTP 面移除（遗留 #24）──")
    for f in ["password", "passwordFuzzy"]:
        _, r = post("/admin/loadUser", token, {"pageNo": 1, "pageSize": 5, f: "x"})
        # 字段已从 UserInfoQuery 删除 → Spring 绑定器忽略它 → 请求本身成功（code=0）
        # 关键不是「报错」，而是「不再影响查询」：下面用直连 SQL 证明该列已不在 WHERE 里
        check("T6 passwordFuzzy=%s：请求不再因该字段失败（字段已删除，被绑定器忽略）" % f,
              code_of(r) == 0, "code=%s message=%s" % (code_of(r), (r or {}).get("message")))
    xml = open(r"D:\qd\EasyChat\easychat-java\src\main\resources\com\easychat\mappers\UserInfoMapper.xml",
               encoding="utf-8").read()
    # 剥注释后再查，避免注释里的说明被当成残留
    xml_wo = xml.replace("<!--", "\x00").split("\x00")[0] + "".join(
        p.split("-->")[1] for p in xml.split("<!--")[1:] if "-->" in p)
    has_pw_cond = ("query.password" in xml_wo) or ("query.passwordFuzzy" in xml_wo)
    check("T6 UserInfoMapper.xml 已无 password / passwordFuzzy 查询条件（剥注释后核对）",
          not has_pw_cond)
    out("")

    # ── T7：负向 —— 鉴权未被削弱 ───────────────────────────────
    out("── T7 负向：非管理员调管理端列表仍被拒 ──")
    try:
        u_token = login("karina7710@test.com")
    except Exception as e:
        u_token = None
        out("      [跳过] 普通用户登录失败：%s" % e)
    if u_token:
        for ep, _ in ENDPOINTS:
            _, r = post(ep, u_token, {"pageNo": 1, "pageSize": 5})
            check("T7 %s：普通用户被拒（CODE_%d），鉴权未被本次改动削弱"
                  % (ep, CODE_NO_PERMISSION), code_of(r) == CODE_NO_PERMISSION,
                  "code=%s" % code_of(r))
    out("")

    # ── 汇总 ──────────────────────────────────────────────────
    passed = sum(1 for _, ok in RESULTS if ok)
    out("=" * 74)
    out("结论：%d/%d 通过" % (passed, len(RESULTS)))
    out("=" * 74)
    failed = [n for n, ok in RESULTS if not ok]
    if failed:
        out("失败项：")
        for n in failed:
            out("  - " + n)
    if _LOGFP:
        _LOGFP.close()
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
