#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「朋友圈可见范围 + 在线状态可见性」活体冒烟（stdlib only，自清理）

覆盖 openspec/changes/2026-10-02-privacy-moment-and-status 验收：
  C1 朋友圈可见范围 0–4 可配置；空白名单/非好友入名单/超长/非法值均被拒
  C2 用户级默认 → 发布页初值；单条临时覆盖不回写用户级
  C3 在线状态可见性开关 0/1 往返
  存量用户 4 列默认值正确且行为不变

账号：A = test@qq.com（设置者 + 申请方），B = karina7710@test.com（好友，用于名单）
前置：已执行 easychat-migration-011-privacy-settings.sql

用法：python smoke_privacy.py [日志文件]
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
C_EMAIL = "3289228667@qq.com"   # 非好友，用于验证「非好友入名单被拒」

A_ID = B_ID = C_ID = None
_ORIGIN = {}
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
                                     data=urllib.parse.urlencode(fields or {}).encode(),
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
    key = data_of(r)["checkCodeKey"]
    code = subprocess.run([REDIS, "GET", "easychat:checkcode:" + key],
                          capture_output=True, text=True).stdout.strip().strip('"')
    r = http_req("/account/login", fields={"email": email, "password": PWD_RAW,
                                          "checkCodeKey": key, "checkCode": code})[1]
    if code_of(r) != 0:
        raise RuntimeError("login %s failed: %s" % (email, r))
    return data_of(r)["token"]


def be_friends():
    """A 与 B 建双向好友关系（供名单子集校验用）"""
    for u, v in ((A_ID, B_ID), (B_ID, A_ID)):
        sql("INSERT INTO user_contact (user_id,contact_id,contact_type,status,role,create_time) "
            "VALUES ('%s','%s',0,1,2,NOW()) ON DUPLICATE KEY UPDATE status=1;" % (u, v))


def cols(user_id):
    """返回 4 列的快照串 'mv|mvl|mil|osv'，用于断言「不串列」"""
    q = ("SELECT CONCAT(IFNULL(moment_visibility,'-'),'|',"
         "IFNULL(moment_visible_list,'-'),'|',"
         "IFNULL(moment_invisible_list,'-'),'|',"
         "IFNULL(online_status_visible,'-')) "
         "FROM user_info WHERE user_id='%s';") % user_id
    return sql1(q)


def cleanup():
    out("\n=== 清理 ===")
    try:
        if A_ID and _ORIGIN:
            sql("UPDATE user_info SET moment_visibility=%s, online_status_visible=%s, "
                "moment_visible_list=%s, moment_invisible_list=%s WHERE user_id='%s';"
                % (_ORIGIN.get("mv", 0), _ORIGIN.get("osv", 1),
                   "NULL" if _ORIGIN.get("mvl") is None else "'%s'" % _ORIGIN.get("mvl"),
                   "NULL" if _ORIGIN.get("mil") is None else "'%s'" % _ORIGIN.get("mil"),
                   A_ID))
        out("   A 的 4 列已还原：mv=%s osv=%s" % (_ORIGIN.get("mv"), _ORIGIN.get("osv")))
    except Exception as e:
        out("   清理失败: %s" % e)
        RESULTS.append(("清理", False))


def main():
    global _LOGFP, A_ID, B_ID, C_ID, _ORIGIN
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")

    out("===== 朋友圈可见范围 + 在线状态可见性 活体冒烟 =====")
    out("时间: %s" % datetime.datetime.now().isoformat(timespec="seconds"))
    out("A=%s  B=%s(好友)  C=%s(非好友)" % (A_EMAIL, B_EMAIL, C_EMAIL))

    try:
        # ── 0. 前置 ───────────────────────���─────────────────
        out("\n=== 0. 前置条件 ===")
        check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
        a_tok, b_tok = login(A_EMAIL), login(B_EMAIL)
        A_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % A_EMAIL)
        B_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % B_EMAIL)
        C_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % C_EMAIL)
        check("A 登录成功 %s" % A_ID, bool(a_tok))
        check("B 登录成功 %s" % B_ID, bool(b_tok))

        # 记录原值
        _ORIGIN = {
            "mv": sql1("SELECT moment_visibility FROM user_info WHERE user_id='%s';" % A_ID),
            "osv": sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % A_ID),
            "mvl": sql1("SELECT moment_visible_list FROM user_info WHERE user_id='%s';" % A_ID) or None,
            "mil": sql1("SELECT moment_invisible_list FROM user_info WHERE user_id='%s';" % A_ID) or None,
        }
        out("   （A 原值 mv=%s osv=%s，结束还原）" % (_ORIGIN["mv"], _ORIGIN["osv"]))

        # 迁移已执行校验：4 列存在
        for col in ("moment_visibility", "moment_visible_list", "moment_invisible_list", "online_status_visible"):
            check("列 %s 已存在（migration-011 已执行）" % col,
                  sql1("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='easychat' "
                       "AND TABLE_NAME='user_info' AND COLUMN_NAME='%s';" % col) == "1")

        be_friends()
        check("A 与 B 已是好友（供名单子集校验）", sql1(
            "SELECT COUNT(*) FROM user_contact WHERE user_id='%s' AND contact_id='%s' AND status=1;"
            % (A_ID, B_ID)) == "1")

        # ── 1. 存量行为不变 ────────────────────────────────
        out("\n=== 1. 存量用户默认值与行为不变 ===")
        for uid, label in ((B_ID, "B"), (C_ID, "C")):
            check("%s 的 moment_visibility 默认 0（公开，与改动前发布页默认一致）" % label,
                  sql1("SELECT moment_visibility FROM user_info WHERE user_id='%s';" % uid) == "0")
            check("%s 的 online_status_visible 默认 1（展示，与改动前无条件广播一致）" % label,
                  sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % uid) == "1")
        r = post("/userInfo/getUserInfo", b_tok)[1]
        info = data_of(r)
        check("getUserInfo 带出 4 个隐私字段",
              all(k in info for k in ("momentVisibility", "momentVisibleList",
                                       "momentInvisibleList", "onlineStatusVisible")),
              "keys=%s" % [k for k in ("momentVisibility", "momentVisibleList",
                                       "momentInvisibleList", "onlineStatusVisible") if k in info])

        # ── 2. C1 可见范围 0–4 ──────────────────────────────
        out("\n=== 2. C1 朋友圈可见范围 0–4 可配置 ===")
        for v in (0, 1, 2):
            r = post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": v})[1]
            check("设为 %d code=0" % v, code_of(r) == 0, r.get("message"))
            check("  DB 写入 %d" % v,
                  sql1("SELECT moment_visibility FROM user_info WHERE user_id='%s';" % A_ID) == str(v))

        r = post("/userInfo/updateMomentPrivacy", a_tok,
                 {"momentVisibility": 3, "visibleList": json.dumps([B_ID])})[1]
        check("设为 3（白名单，含好友 B）code=0", code_of(r) == 0, r.get("message"))
        check("  白名单已落库且含 B",
              B_ID in (sql1("SELECT moment_visible_list FROM user_info WHERE user_id='%s';" % A_ID) or ""),
              sql1("SELECT moment_visible_list FROM user_info WHERE user_id='%s';" % A_ID))

        r = post("/userInfo/updateMomentPrivacy", a_tok,
                 {"momentVisibility": 4, "invisibleList": json.dumps([B_ID])})[1]
        check("设为 4（黑名单，含好友 B）code=0", code_of(r) == 0, r.get("message"))
        check("  黑名单已落库且含 B",
              B_ID in (sql1("SELECT moment_invisible_list FROM user_info WHERE user_id='%s';" % A_ID) or ""))

        # 切回公开时不应清空已有名单
        post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 0})
        check("★ 切回公开后白名单仍保留（便于切回）",
              sql1("SELECT moment_visible_list FROM user_info WHERE user_id='%s';" % A_ID) is not None)
        post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 3,
                                                      "visibleList": json.dumps([B_ID])})

        # ── 3. C1 校验与拒绝 ────────────────────────────────
        out("\n=== 3. C1 校验：空名单 / 非好友 / 非法值 / 超长 ===")
        r = post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 3})[1]
        check("★ visibility=3 但白名单为空 → CODE_1001", code_of(r) == 1001, "code=%s" % code_of(r))
        r = post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 4, "invisibleList": "[]"})[1]
        check("★ visibility=4 但黑名单为 [] → CODE_1001", code_of(r) == 1001, "code=%s" % code_of(r))
        check("★ 被拒后 DB 仍为上一次合法值 3",
              sql1("SELECT moment_visibility FROM user_info WHERE user_id='%s';" % A_ID) == "3")

        r = post("/userInfo/updateMomentPrivacy", a_tok,
                 {"momentVisibility": 3, "visibleList": json.dumps([C_ID])})[1]
        check("★ 名单含非好友 C → CODE_1001", code_of(r) == 1001, "code=%s" % code_of(r))

        r = post("/userInfo/updateMomentPrivacy", a_tok,
                 {"momentVisibility": 3, "visibleList": "not-a-json"})[1]
        check("★ 非法 JSON → CODE_1001", code_of(r) == 1001, "code=%s" % code_of(r))

        r = post("/userInfo/updateMomentPrivacy", a_tok,
                 {"momentVisibility": 3, "visibleList": json.dumps(["U_f%d" % i for i in range(8000)])})[1]
        check("★ 超长名单 → CODE_1001", code_of(r) == 1001, "code=%s" % code_of(r))

        for bad in (-1, 5, 99):
            r = post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": bad})[1]
            check("非法 visibility=%d → CODE_1001" % bad, code_of(r) == 1001, "code=%s" % code_of(r))

        r = post("/userInfo/updateMomentPrivacy", a_tok, {"visibleList": "[]"})[1]
        check("visibility 缺省 → HTTP 400", http_req("/userInfo/updateMomentPrivacy", token=a_tok,
                                                     fields={"visibleList": "[]"})[0] == 400)

        # ── 4. 只写隐私列（不串列）──────────────────────────
        out("\n=== 4. 只写隐私列，不串列 ===")
        before = cols(A_ID)
        post("/userInfo/updateOnlineStatusVisible", a_tok, {"visible": 1})
        after = cols(A_ID)
        check("★ 改在线状态开关不重置朋友圈可见范围（前三段不变）",
              before.split("|")[0] == after.split("|")[0] and before.split("|")[1] == after.split("|")[1],
              "before=%s after=%s" % (before, after))
        before = cols(A_ID)
        post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 2})
        after = cols(A_ID)
        check("★ 改朋友圈可见范围不重置在线状态开关（第四段不变）",
              before.split("|")[3] == after.split("|")[3],
              "before=%s after=%s" % (before, after))
        nick = sql1("SELECT nick_name FROM user_info WHERE user_id='%s';" % A_ID)
        check("★ 昵称未被隐私设置覆盖", bool(nick), "nick=%s" % nick)

        # ── 5. C2 用户级默认 → 发布初值 ─────────────────────
        out("\n=== 5. C2 用户级默认被发布页继承（端到端）===")
        post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 1})
        r = post("/userInfo/getUserInfo", a_tok)[1]
        check("getUserInfo 返回 momentVisibility=1（发布页据此设初值）",
              data_of(r).get("momentVisibility") == 1,
              "momentVisibility=%s" % data_of(r).get("momentVisibility"))

        post("/userInfo/updateMomentPrivacy", a_tok, {"momentVisibility": 3,
                                                      "visibleList": json.dumps([B_ID])})
        r = post("/userInfo/getUserInfo", a_tok)[1]
        check("白名单模式：getUserInfo 带出名单供发布页预选",
              B_ID in (data_of(r).get("momentVisibleList") or ""),
              "list=%s" % data_of(r).get("momentVisibleList"))

        # 单条发布临时覆盖 → 不回写用户级
        r = post("/moment/publish", a_tok,
                 {"content": "冒烟-单条临时覆盖", "visibility": 2})[1]
        check("单条发布 visibility=2 code=0", code_of(r) == 0, r.get("message"))
        moment_id = data_of(r).get("id")
        check("★ 单条覆盖后用户级仍为 3（未被回写）",
              sql1("SELECT moment_visibility FROM user_info WHERE user_id='%s';" % A_ID) == "3",
              "user_mv=%s" % sql1("SELECT moment_visibility FROM user_info WHERE user_id='%s';" % A_ID))
        check("★ 该条动态自身 visibility=2（判定走单条，不受用户级影响）",
              sql1("SELECT visibility FROM moment WHERE id='%s';" % moment_id) == "2")
        if moment_id:
            sql("DELETE FROM moment_media WHERE moment_id='%s';" % moment_id)
            sql("DELETE FROM moment WHERE id='%s';" % moment_id)

        # ── 6. C3 在线状态可见性 ────────────────────────────
        out("\n=== 6. C3 在线状态可见性开关 ===")
        r = post("/userInfo/updateOnlineStatusVisible", a_tok, {"visible": 0})[1]
        check("关闭 code=0", code_of(r) == 0, r.get("message"))
        check("  DB 写入 0", sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % A_ID) == "0")
        r = post("/userInfo/getUserInfo", a_tok)[1]
        check("getUserInfo 回读 0", data_of(r).get("onlineStatusVisible") == 0)

        r = post("/userInfo/updateOnlineStatusVisible", a_tok, {"visible": 1})[1]
        check("重新开启 code=0", code_of(r) == 0, r.get("message"))
        check("  DB 写入 1", sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % A_ID) == "1")

        for bad in (-1, 2):
            r = post("/userInfo/updateOnlineStatusVisible", a_tok, {"visible": bad})[1]
            check("非法 visible=%d → CODE_1001" % bad, code_of(r) == 1001, "code=%s" % code_of(r))
        check("★ 非法值未污染 DB（仍为 1）",
              sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % A_ID) == "1")

        # ── 7. 越权防护 ─────────────────────────────────────
        out("\n=== 7. 越权防护：接口不接受 userId ===")
        r = post("/userInfo/updateOnlineStatusVisible", a_tok, {"visible": 0, "userId": B_ID})[1]
        check("携带他人 userId 时仍只改自己（B 的值未变）",
              sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % B_ID) == "1",
              "B_osv=%s" % sql1("SELECT online_status_visible FROM user_info WHERE user_id='%s';" % B_ID))
        post("/userInfo/updateOnlineStatusVisible", a_tok, {"visible": 1})

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
