#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「加我方式 + 黑名单管理」活体冒烟（stdlib only，自清理）

覆盖 openspec/changes/2026-10-02-join-type-and-blacklist 验收：
  C1 加我方式可更新并立即对新申请生效（join_type 直读 DB，无缓存）
  C2 黑名单列表可查（含昵称；「被拉黑」不入列；群组不入列）
  C3 黑名单可解除（双向行均删除、缓存清理、可重新申请）
  安全红线  无权解除「别人对我的拉黑」（2401，且双方行均不被删）

环境约束：本机仅 test@qq.com 与 karina7710@test.com 可用统一口令登录。
  A = test@qq.com              （加我方式设置者 + 黑名单使用方）
  B = karina7710@test.com       （申请方 / 被拉黑方）
  C = 3289228667@qq.com         （不可登录，仅作申请单与 SQL 断言对象）

全部状态变更在 finally 中还原。
用法：python smoke_blacklist.py [日志文件]
"""
import sys
import subprocess
import urllib.request
import urllib.parse
import urllib.error
import json
import datetime

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
PWD_RAW = "Test@123456"

A_EMAIL = "test@qq.com"
B_EMAIL = "karina7710@test.com"
C_EMAIL = "3289228667@qq.com"

A_ID = B_ID = C_ID = None
_ORIGIN_JOIN_TYPE = None
_LOGFP = None
RESULTS = []


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


# ── 基础设施 ────────────────────────────────────────────────
def sql(query):
    p = subprocess.run(
        [MYSQL, "-uroot", "-proot", "-h127.0.0.1", "--default-character-set=utf8mb4",
         "easychat", "-N", "-B", "-e", query],
        capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("mysql failed: %s" % p.stderr.decode("utf-8", "replace"))
    return p.stdout.decode("utf-8", "replace").strip()


def sql1(query):
    v = sql(query)
    return v.split("\n")[0] if v else ""


def http_req(path, token=None, fields=None, get=False):
    headers = {}
    if token:
        headers["token"] = token
    if get or fields is None:
        req = urllib.request.Request(BASE + path, headers=headers, method="GET")
    else:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        req = urllib.request.Request(BASE + path,
                                     data=urllib.parse.urlencode(fields).encode(),
                                     headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"code": -1, "message": body[:200]}


def post(path, token, fields=None):
    return http_req(path, token=token, fields=fields or {})


def code_of(res):
    return res.get("code") if isinstance(res, dict) else None


def data_of(res):
    d = (res or {}).get("data")
    return {} if d is None else d


def login(email):
    r = http_req("/account/checkCode", get=True)[1]
    if code_of(r) != 0:
        raise RuntimeError("checkCode failed: %s" % r)
    key = data_of(r)["checkCodeKey"]
    code = subprocess.run([REDIS, "GET", "easychat:checkcode:" + key],
                          capture_output=True, text=True).stdout.strip().strip('"')
    r = http_req("/account/login", fields={"email": email, "password": PWD_RAW,
                                          "checkCodeKey": key, "checkCode": code})[1]
    if code_of(r) != 0:
        raise RuntimeError("login %s failed: %s" % (email, r))
    return data_of(r)["token"]


# ── 关系行操作 ──────────────────────────────────────────────
def rel_status(user_id, contact_id):
    """返回 (我→他) 关系行状态，不存在返回空串"""
    return sql1("SELECT status FROM user_contact WHERE user_id='%s' AND contact_id='%s';"
                % (user_id, contact_id))


def drop_rel(user_id, contact_id):
    sql("DELETE FROM user_contact WHERE user_id='%s' AND contact_id='%s';" % (user_id, contact_id))


def set_join_type(user_id, value):
    sql("UPDATE user_info SET join_type=%d WHERE user_id='%s';" % (value, user_id))


def cleanup():
    out("\n=== 清理 ===")
    try:
        for u in (A_ID, B_ID, C_ID):
            for v in (A_ID, B_ID, C_ID):
                if u and v and u != v:
                    drop_rel(u, v)
        if A_ID and _ORIGIN_JOIN_TYPE is not None:
            set_join_type(A_ID, int(_ORIGIN_JOIN_TYPE))
        out("   临时关系行已清、A 的 join_type 已还原为 %s" % _ORIGIN_JOIN_TYPE)
    except Exception as e:
        out("   清理失败，请手工核对: %s" % e)
        RESULTS.append(("清理", False))


def main():
    global _LOGFP, A_ID, B_ID, C_ID, _ORIGIN_JOIN_TYPE
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")

    out("===== 加我方式 + 黑名单管理 活体冒烟 =====")
    out("时间: %s" % datetime.datetime.now().isoformat(timespec="seconds"))
    out("A=%s(设置者)  B=%s(申请/被拉黑方)  C=%s(仅SQL断言)" % (A_EMAIL, B_EMAIL, C_EMAIL))

    try:
        # ── 0. 前置 ──────────────────────────────────────────
        out("\n=== 0. 前置条件 ===")
        check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
        a_tok, b_tok = login(A_EMAIL), login(B_EMAIL)
        A_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % A_EMAIL)
        B_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % B_EMAIL)
        C_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % C_EMAIL)
        check("A 登录成功 %s" % A_ID, bool(a_tok))
        check("B 登录成功 %s" % B_ID, bool(b_tok))
        _ORIGIN_JOIN_TYPE = sql1("SELECT join_type FROM user_info WHERE user_id='%s';" % A_ID)
        out("   （A 原 join_type=%s，冒烟结束还原）" % _ORIGIN_JOIN_TYPE)
        for u in (A_ID, B_ID, C_ID):
            for v in (A_ID, B_ID, C_ID):
                if u != v:
                    drop_rel(u, v)

        # ── 1. C1 加我方式可更新 ─────────────────────────────
        out("\n=== 1. C1 加我方式可更新 ===")
        r = post("/userInfo/updateJoinType", a_tok, {"joinType": 1})[1]
        check("更新为「加我时需验证」(1) code=0", code_of(r) == 0, r.get("message"))
        check("DB 已写入 1", sql1("SELECT join_type FROM user_info WHERE user_id='%s';" % A_ID) == "1")

        r = post("/userInfo/updateJoinType", a_tok, {"joinType": 0})[1]
        check("更新为「直接加入」(0) code=0", code_of(r) == 0, r.get("message"))
        check("DB 已写入 0", sql1("SELECT join_type FROM user_info WHERE user_id='%s';" % A_ID) == "0")

        for bad in (2, -1):
            r = post("/userInfo/updateJoinType", a_tok, {"joinType": bad})[1]
            check("非法 joinType=%d → CODE_1001" % bad, code_of(r) == 1001, "code=%s" % code_of(r))
        check("★ 非法值未污染 DB（仍为 0）",
              sql1("SELECT join_type FROM user_info WHERE user_id='%s';" % A_ID) == "0")

        # 立即生效：B 申请加 A，join_type=0 → 直接成为好友
        out("\n=== 2. C1 保存后立即对新申请生效 ===")
        r = post("/contact/applyAdd", b_tok, {"contactId": A_ID, "contactType": "USER"})[1]
        check("B 申请加 A 返回 code=0", code_of(r) == 0, r.get("message"))
        check("join_type=0 时 B 直接成为好友（返回 0）", data_of(r) == 0, "data=%s" % data_of(r))
        check("DB 中 A→B 已是好友 status=1", rel_status(A_ID, B_ID) == "1")

        # 改回 1 后，C 再申请 → 应落申请单
        post("/userInfo/updateJoinType", a_tok, {"joinType": 1})
        drop_rel(A_ID, B_ID)
        drop_rel(B_ID, A_ID)
        r = post("/contact/applyAdd", b_tok, {"contactId": A_ID, "contactType": "USER"})[1]
        check("改为 1 后 B 再次申请返回 1（需审批）", data_of(r) == 1, "data=%s" % data_of(r))
        check("已落待处理申请单", sql1("SELECT COUNT(*) FROM user_contact_apply WHERE "
                                      "apply_user_id='%s' AND contact_id='%s' AND contact_type=0;"
                                      % (B_ID, A_ID)) == "1")
        sql("DELETE FROM user_contact_apply WHERE apply_user_id='%s' AND contact_id='%s';" % (B_ID, A_ID))

        # ── 3. C2 黑名单列表 ────────────────────────────────
        out("\n=== 3. C2 黑名单列表可查 ===")
        r = post("/contact/loadBlackList", a_tok)[1]
        check("空黑名单返回 code=0 且空列表", code_of(r) == 0 and data_of(r) == [],
              "code=%s len=%d" % (code_of(r), len(data_of(r))))

        r = post("/contact/addContact2BlackList", a_tok, {"contactId": B_ID})[1]
        check("A 拉黑 B code=0", code_of(r) == 0, r.get("message"))
        check("A→B = 4 BLACKLIST", rel_status(A_ID, B_ID) == "4")
        check("B→A = 5 BLACKLIST_BE（双向生效）", rel_status(B_ID, A_ID) == "5")

        r = post("/contact/loadBlackList", a_tok)[1]
        lst = data_of(r)
        check("黑名单含 B", any(x.get("contactId") == B_ID for x in lst), "len=%d" % len(lst))
        item = next((x for x in lst if x.get("contactId") == B_ID), {})
        check("含对方昵称 contactName", bool(item.get("contactName")),
              "contactName=%s" % item.get("contactName"))
        check("★ 「被拉黑」(status=5) 不入列（B→A 那行）",
              all(x.get("contactId") != A_ID for x in lst))

        # B 拉黑 C：C 的列表里只有 C，验证互不串号
        post("/contact/addContact2BlackList", b_tok, {"contactId": C_ID})
        r = post("/contact/loadBlackList", a_tok)[1]
        check("★ 只返回自己的黑名单（不含 B 拉黑的 C）",
              all(x.get("contactId") != C_ID for x in data_of(r)))
        r = post("/contact/loadBlackList", b_tok)[1]
        check("B 的黑名单含 C", any(x.get("contactId") == C_ID for x in data_of(r)))
        drop_rel(B_ID, C_ID)
        drop_rel(C_ID, B_ID)

        # ── 4. C3 解除黑名单 ────────────────────────────────
        out("\n=== 4. C3 解除黑名单（双向删行 + 可重新申请）===")
        r = post("/contact/removeBlackList", a_tok, {"contactId": B_ID})[1]
        check("解除 code=0", code_of(r) == 0, r.get("message"))
        check("★ 我→他 的行已删除", rel_status(A_ID, B_ID) == "")
        check("★ 他→我 的行也删除（无单向残留）", rel_status(B_ID, A_ID) == "")
        r = post("/contact/loadBlackList", a_tok)[1]
        check("列表已清空", data_of(r) == [], "len=%d" % len(data_of(r)))

        r = post("/contact/removeBlackList", a_tok, {"contactId": B_ID})[1]
        check("重复解除 → CODE_2401", code_of(r) == 2401, "code=%s" % code_of(r))

        r = post("/contact/removeBlackList", a_tok, {"contactId": "U_not_exist"})[1]
        check("解除无关系对象 → CODE_2401", code_of(r) == 2401, "code=%s" % code_of(r))

        r = post("/contact/applyAdd", b_tok, {"contactId": A_ID, "contactType": "USER"})[1]
        check("★ 解除后 B 可重新申请（落申请单，非直接加）", data_of(r) == 1, "data=%s" % data_of(r))
        sql("DELETE FROM user_contact_apply WHERE apply_user_id='%s' AND contact_id='%s';" % (B_ID, A_ID))
        drop_rel(A_ID, B_ID)
        drop_rel(B_ID, A_ID)

        # ── 5. 安全红线 ─────────────────────────────────────
        out("\n=== 5. 安全红线 无权解除「别人对我的拉黑」 ===")
        # 造：B 拉黑 A  → A→B=5 BLACKLIST_BE，B→A=4 BLACKLIST
        sql("INSERT INTO user_contact (user_id,contact_id,contact_type,status,create_time) "
            "VALUES ('%s','%s',0,5,NOW()),('%s','%s',0,4,NOW()) "
            "ON DUPLICATE KEY UPDATE status=VALUES(status);" % (A_ID, B_ID, B_ID, A_ID))
        check("前置：A→B=5 BLACKLIST_BE（他拉黑我）", rel_status(A_ID, B_ID) == "5")
        r = post("/contact/loadBlackList", a_tok)[1]
        check("★ 该对象不出现在我的黑名单", all(x.get("contactId") != B_ID for x in data_of(r)))
        r = post("/contact/removeBlackList", a_tok, {"contactId": B_ID})[1]
        check("★ 我无法解除 → CODE_2401", code_of(r) == 2401, "code=%s" % code_of(r))
        check("★ 双方关系行均未被删除（他的拉黑仍有效）",
              rel_status(A_ID, B_ID) == "5" and rel_status(B_ID, A_ID) == "4",
              "A→B=%s B→A=%s" % (rel_status(A_ID, B_ID), rel_status(B_ID, A_ID)))

        # 双向拉黑：我解除时反向行（他拉黑我，status=4）必须保留
        out("\n=== 5b. 双向拉黑时反向行保留 ===")
        sql("UPDATE user_contact SET status=4 WHERE user_id='%s' AND contact_id='%s';" % (A_ID, B_ID))
        sql("UPDATE user_contact SET status=4 WHERE user_id='%s' AND contact_id='%s';" % (B_ID, A_ID))
        r = post("/contact/removeBlackList", a_tok, {"contactId": B_ID})[1]
        check("我方解除 code=0", code_of(r) == 0, "code=%s" % code_of(r))
        check("我的拉黑行已删", rel_status(A_ID, B_ID) == "")
        check("★ 对方拉黑我的那行保留（不归我处置）", rel_status(B_ID, A_ID) == "4",
              "B→A=%s" % rel_status(B_ID, A_ID))

        # ── 6. 群组不入黑名单 ────────────────────────────────
        out("\n=== 6. 群组不进入黑名单 ===")
        r = post("/contact/loadBlackList", a_tok)[1]
        check("列表内无 groupId 形态的 contactId",
              all(str(x.get("contactId", "")).startswith("U") for x in data_of(r)),
              "ids=%s" % [x.get("contactId") for x in data_of(r)])

    except Exception as e:
        out("\n!! 冒烟异常中断: %r" % e)
        RESULTS.append(("冒烟执行未正常结束", False))
    finally:
        cleanup()

    passed = sum(1 for _, ok in RESULTS if ok)
    out("\n===== 结论：%d/%d 通过 =====" % (passed, len(RESULTS)))
    if passed != len(RESULTS):
        for name, ok in RESULTS:
            if not ok:
                out("  FAILED: %s" % name)
    if _LOGFP:
        _LOGFP.close()
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
