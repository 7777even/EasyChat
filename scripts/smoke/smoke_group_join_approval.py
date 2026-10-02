#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「群入群审批闭环」活体冒烟（stdlib only，自清理）

覆盖 openspec/changes/2026-10-02-group-join-approval 验收：
  C1 群二维码/邀请链接入群受 group_info.join_type 管辖
     （修复前 joinByQrCode/joinByInvite 直接 addContact 入群，群主所设权限形同虚设）
  C2 两个 join 端点返回 joinType（0=已直接加入 / 1=已提交申请待审批）
  C3 群入群申请审批人是群主或群管理员；管理员在申请列表可见；普通成员既不可见也不可审
  C4 无效 token 返回 1001，不得被客户端误报为「已加入」
  回归护栏 好友申请（contactType=USER）审批人仍为 receive_user_id 本人，未被放宽

环境约束（重要）：本机仅 2 个账号可用统一测试口令登录，故用两个临时群拆分场景：
  账号 A = test@qq.com        可登录
  账号 B = karina7710@test.com 可登录
  账号 C = 3289228667@qq.com   不可登录，仅作「申请单申请人」由 SQL 断言其入群结果

  G_APPLY 群（B 任群主）：A 作为「扫码申请者」，验证 C1/C2/C4
  G_ADMIN 群（B 任群主）：A 作为成员（role 2→1），C 的申请单由 SQL 造，验证 C3

前置：后端 5050 已启动、MySQL/Redis 可用。
用法：python smoke_group_join_approval.py [日志文件]
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

A_EMAIL = "test@qq.com"                 # 登录账号 A
B_EMAIL = "karina7710@test.com"          # 登录账号 B（充当两个临时群的群主）
C_EMAIL = "3289228667@qq.com"            # 不可登录，仅作申请单申请人
G_APPLY = "GSMKJOIN0001"
G_ADMIN = "GSMKJOIN0002"
G_NAME = "冒烟临时群"

A_ID = B_ID = C_ID = None
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
    """取 Result<T> 的 data。注意不能写成 `or {}`——joinType=0 是合法值且为 falsy。"""
    d = (res or {}).get("data")
    return {} if d is None else d


def login(email):
    r = http_req("/account/checkCode", get=True)[1]
    if code_of(r) != 0:
        raise RuntimeError("checkCode failed: %s" % r)
    key = data_of(r)["checkCodeKey"]
    p = subprocess.run([REDIS, "GET", "easychat:checkcode:" + key],
                       capture_output=True, text=True)
    code = p.stdout.strip().strip('"')
    if not code:
        raise RuntimeError("captcha empty")
    r = http_req("/account/login", fields={"email": email, "password": PWD_RAW,
                                          "checkCodeKey": key, "checkCode": code})[1]
    if code_of(r) != 0:
        raise RuntimeError("login %s failed: %s" % (email, r))
    return data_of(r)["token"]


# ── 临时数据 ────────────────────────────────────────────────
def create_group(group_id, owner_id, join_type):
    sql("INSERT INTO group_info (group_id,group_name,group_owner_id,create_time,join_type,status) "
        "VALUES ('%s','%s','%s',NOW(),%d,1) ON DUPLICATE KEY UPDATE group_owner_id='%s',join_type=%d,status=1;"
        % (group_id, G_NAME, owner_id, join_type, owner_id, join_type))


def add_member(user_id, group_id, role):
    sql("INSERT INTO user_contact (user_id,contact_id,contact_type,status,role,create_time) "
        "VALUES ('%s','%s',1,1,%d,NOW()) ON DUPLICATE KEY UPDATE status=1,role=%d;"
        % (user_id, group_id, role, role))


def drop_member(user_id, group_id):
    sql("DELETE FROM user_contact WHERE user_id='%s' AND contact_id='%s';" % (user_id, group_id))


def is_member(user_id, group_id):
    return sql1("SELECT COUNT(*) FROM user_contact WHERE user_id='%s' AND contact_id='%s' "
                "AND contact_type=1 AND status=1;" % (user_id, group_id)) == "1"


def set_join_type(group_id, value):
    sql("UPDATE group_info SET join_type=%d WHERE group_id='%s';" % (value, group_id))


def set_role(user_id, group_id, role):
    sql("UPDATE user_contact SET role=%d WHERE user_id='%s' AND contact_id='%s';"
        % (role, user_id, group_id))


def make_apply(apply_user_id, receive_user_id, group_id, contact_type):
    sql("INSERT INTO user_contact_apply (apply_user_id,receive_user_id,contact_type,contact_id,"
        "last_apply_time,status,apply_info) VALUES ('%s','%s',%d,'%s',NOW(),0,'冒烟造申请单');"
        % (apply_user_id, receive_user_id, contact_type, group_id))
    return sql1("SELECT MAX(apply_id) FROM user_contact_apply WHERE apply_user_id='%s' "
                "AND contact_id='%s';" % (apply_user_id, group_id))


def apply_count(apply_user_id, group_id, status=0):
    return int(sql1("SELECT COUNT(*) FROM user_contact_apply WHERE apply_user_id='%s' "
                    "AND contact_id='%s' AND status=%d;" % (apply_user_id, group_id, status)) or "0")


def find_apply(apply_list, apply_id, contact_type=None):
    for a in apply_list or []:
        if a.get("applyId") == int(apply_id):
            if contact_type is None or a.get("contactType") == contact_type:
                return a
    return None


def load_apply(token):
    r = post("/contact/loadApply", token, {"pageNo": 1})[1]
    return data_of(r).get("list") or [], data_of(r).get("total")


def cleanup():
    out("\n=== 清理临时数据 ===")
    try:
        for g in (G_APPLY, G_ADMIN):
            for uid in (A_ID, B_ID, C_ID):
                if uid:
                    drop_member(uid, g)
            sql("DELETE FROM user_contact_apply WHERE contact_id='%s';" % g)
        # 兜底：清掉 C↔B 好友申请可能产生的双向好友行
        if C_ID and B_ID:
            sql("DELETE FROM user_contact WHERE (user_id='%s' AND contact_id='%s') "
                "OR (user_id='%s' AND contact_id='%s');" % (C_ID, B_ID, B_ID, C_ID))
            sql("DELETE FROM user_contact_apply WHERE apply_user_id='%s' OR receive_user_id='%s';"
                % (C_ID, C_ID))
        # chat_session 无 group_id 列，会话以 session_id 关联 chat_session_user
        sql("DELETE FROM chat_message WHERE session_id IN "
            "(SELECT session_id FROM chat_session_user WHERE user_id IN ('%s','%s','%s'));"
            % (A_ID, B_ID, C_ID))
        sql("DELETE FROM chat_session_user WHERE user_id IN ('%s','%s','%s') AND contact_id IN ('%s','%s');"
            % (A_ID, B_ID, C_ID, G_APPLY, G_ADMIN))
        sql("DELETE FROM chat_session WHERE session_id NOT IN (SELECT session_id FROM chat_session_user);")
        for g in (G_APPLY, G_ADMIN):
            sql("DELETE FROM group_info WHERE group_id='%s';" % g)
        out("   临时群、成员行、申请单、会话均已删除")
        left = sql1("SELECT COUNT(*) FROM group_info WHERE group_id IN ('%s','%s');"
                    % (G_APPLY, G_ADMIN))
        check("清理后临时群已不存在", left == "0")
    except Exception as e:
        out("   清理失败，请手工核对: %s" % e)
        RESULTS.append(("清理临时数据", False))


def main():
    global _LOGFP, A_ID, B_ID, C_ID
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")

    out("===== group-join-approval 活体冒烟 =====")
    out("时间: %s" % datetime.datetime.now().isoformat(timespec="seconds"))
    out("BASE=%s" % BASE)
    out("A=%s  B=%s  C=%s(仅SQL断言)" % (A_EMAIL, B_EMAIL, C_EMAIL))
    out("G_APPLY=%s  G_ADMIN=%s" % (G_APPLY, G_ADMIN))

    try:
        # ── 0. 前置 ──────────────────────────────────────────
        out("\n=== 0. 前置条件 ===")
        check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
        a_tok, b_tok = login(A_EMAIL), login(B_EMAIL)
        A_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % A_EMAIL)
        B_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % B_EMAIL)
        C_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % C_EMAIL)
        check("账号 A 登录成功 %s" % A_ID, bool(a_tok))
        check("账号 B 登录成功 %s" % B_ID, bool(b_tok))
        check("账号 C 存在于库 %s" % C_ID, bool(C_ID))

        cleanup_silent = True
        create_group(G_APPLY, B_ID, 1)
        create_group(G_ADMIN, B_ID, 1)
        add_member(B_ID, G_APPLY, 0)      # B 为 G_APPLY 群主
        add_member(B_ID, G_ADMIN, 0)      # B 为 G_ADMIN 群主
        add_member(A_ID, G_ADMIN, 2)      # A 先作为普通成员
        check("两个临时群已建（A 在 G_ADMIN 中为普通成员 role=2）",
              sql1("SELECT role FROM user_contact WHERE user_id='%s' AND contact_id='%s';"
                   % (A_ID, G_ADMIN)) == "2")
        check("A 当前不是 G_APPLY 成员（申请者前置）", not is_member(A_ID, G_APPLY))

        # ── 1. C1/C2：join_type=1 扫码必须被拦 ───────────────
        out("\n=== 1. C1/C2 扫码入群受 join_type 管辖（join_type=1）===")
        r = post("/group/qrCode/generate", b_tok, {"groupId": G_APPLY})[1]
        qr = data_of(r)
        check("群主 B 生成二维码成功", code_of(r) == 0 and bool(qr), "code=%s" % code_of(r))

        r = post("/group/qrCode/join", a_tok, {"qrCodeToken": qr})[1]
        check("扫码入群 code=0", code_of(r) == 0, r.get("message"))
        check("返回 joinType=1（已提交申请待审批）", data_of(r) == 1,
              "data=%s" % data_of(r))
        check("★ 关键：申请人 A **未**被直接拉入群（漏洞已堵）", not is_member(A_ID, G_APPLY))
        check("已落待处理申请单（status=0，contact_type=1）", apply_count(A_ID, G_APPLY) == 1,
              "count=%d" % apply_count(A_ID, G_APPLY))
        check("申请单 receive_user_id 为群主 B",
              sql1("SELECT receive_user_id FROM user_contact_apply WHERE apply_user_id='%s' "
                   "AND contact_id='%s';" % (A_ID, G_APPLY)) == B_ID)
        check("申请附言标注来源为群二维码",
              "群二维码" in sql1("SELECT apply_info FROM user_contact_apply WHERE apply_user_id='%s' "
                                 "AND contact_id='%s';" % (A_ID, G_APPLY)))

        # 幂等：重复扫码不新增行
        r = post("/group/qrCode/join", a_tok, {"qrCodeToken": qr})[1]
        check("重复扫码幂等：仍只有 1 条申请单", apply_count(A_ID, G_APPLY) == 1,
              "count=%d" % apply_count(A_ID, G_APPLY))
        check("重复扫码仍返回 joinType=1", data_of(r) == 1)

        # ── 2. 群主可见 + 审批通过 ───────────────────────────
        out("\n=== 2. C3 群主可见入群申请并审批通过 ===")
        lst, total = load_apply(b_tok)
        aid = apply_count and sql1("SELECT MAX(apply_id) FROM user_contact_apply "
                                   "WHERE apply_user_id='%s' AND contact_id='%s';" % (A_ID, G_APPLY))
        found = find_apply(lst, aid, contact_type=1)
        check("群主 B 在申请列表可见本群入群申请", bool(found),
              "list=%d 条" % len(lst))
        check("入群申请 contactName 为群名（非申请者昵称）",
              bool(found) and found.get("contactName") == G_NAME,
              "contactName=%s" % (found.get("contactName") if found else "-"))
        check("分页 total 与列表同源（total >= list）", total is None or int(total) >= len(lst),
              "total=%s list=%d" % (total, len(lst)))

        r = post("/contact/dealWithApply", b_tok, {"applyId": aid, "status": 1})[1]
        check("群主审批通过 code=0", code_of(r) == 0, "code=%s msg=%s" % (code_of(r), r.get("message")))
        check("★ 审批通过后 A 真正入群", is_member(A_ID, G_APPLY))
        lst2, _ = load_apply(b_tok)
        done = find_apply(lst2, aid)
        check("已处理申请状态变为 1 已同意（loadApply 返回全部状态，非仅待处理）",
              bool(done) and done.get("status") == 1,
              "status=%s" % (done.get("status") if done else "-"))

        # ── 3. 已是成员 / 无效 token ─────────────────────────
        out("\n=== 3. C4 已是成员与无效 token 不得误报为已加入 ===")
        r = post("/group/qrCode/join", a_tok, {"qrCodeToken": qr})[1]
        check("已是群成员再扫码 → 1001", code_of(r) == 1001, "code=%s msg=%s" % (code_of(r), r.get("message")))
        r = post("/group/qrCode/join", a_tok, {"qrCodeToken": "not_exist"})[1]
        check("无效二维码 token → 1001", code_of(r) == 1001, "code=%s" % code_of(r))
        r = post("/group/invite/join", a_tok, {"inviteToken": "not_exist"})[1]
        check("无效邀请 token → 1001", code_of(r) == 1001, "code=%s" % code_of(r))

        # ── 4. join_type=0 直接入群 ──────────────────────────
        out("\n=== 4. C1/C2 join_type=0 时扫码直接入群 ===")
        drop_member(A_ID, G_APPLY)
        set_join_type(G_APPLY, 0)
        r = post("/group/qrCode/generate", b_tok, {"groupId": G_APPLY})[1]
        qr0 = data_of(r)
        r = post("/group/qrCode/join", a_tok, {"qrCodeToken": qr0})[1]
        check("扫码入群 code=0", code_of(r) == 0, r.get("message"))
        check("返回 joinType=0（已直接加入）", data_of(r) == 0,
              "data=%s" % data_of(r))
        check("申请人 A 已直接入群", is_member(A_ID, G_APPLY))
        check("join_type=0 时不产生申请单", apply_count(A_ID, G_APPLY) == 0)
        drop_member(A_ID, G_APPLY)

        # ── 5. 邀请链接路径对称 ──────────────────────────────
        out("\n=== 5. C1 邀请链接路径与二维码路径对称 ===")
        set_join_type(G_APPLY, 1)
        r = post("/group/invite/generate", b_tok, {"groupId": G_APPLY})[1]
        inv = data_of(r)
        check("群主 B 生成邀请链接成功", code_of(r) == 0 and bool(inv))
        r = post("/group/invite/join", a_tok, {"inviteToken": inv})[1]
        check("邀请入群返回 joinType=1", code_of(r) == 0 and data_of(r) == 1,
              "code=%s data=%s" % (code_of(r), data_of(r)))
        check("★ 邀请路径同样未直接入群", not is_member(A_ID, G_APPLY))
        check("申请附言标注来源为邀请链接",
              "邀请链接" in sql1("SELECT apply_info FROM user_contact_apply WHERE apply_user_id='%s' "
                                 "AND contact_id='%s';" % (A_ID, G_APPLY)))
        sql("DELETE FROM user_contact_apply WHERE apply_user_id='%s' AND contact_id='%s';"
            % (A_ID, G_APPLY))

        # ── 6. C3 普通成员不可见且不可审 ─────────────────────
        out("\n=== 6. C3 普通群成员：既不可见也不可审 ===")
        c_apply = make_apply(C_ID, B_ID, G_ADMIN, 1)
        check("已为 C 造一条 G_ADMIN 入群申请单", bool(c_apply), "applyId=%s" % c_apply)

        lst, _ = load_apply(a_tok)
        check("普通成员 A 看不到本群入群申请（修复前也看不到，现仍应看不到）",
              find_apply(lst, c_apply, contact_type=1) is None)
        r = post("/contact/dealWithApply", a_tok, {"applyId": c_apply, "status": 1})[1]
        check("普通成员 A 审批被拒 code=2305", code_of(r) == 2305,
              "code=%s msg=%s" % (code_of(r), r.get("message")))
        check("★ 被拒后 C 未入群", not is_member(C_ID, G_ADMIN))
        check("★ 被拒后申请单仍为待处理",
              sql1("SELECT status FROM user_contact_apply WHERE apply_id='%s';" % c_apply) == "0")

        # ── 7. C3 群管理员可见且可审（修复前不可能）──────────
        out("\n=== 7. C3 群管理员：可见 + 可审批（修复前的空白能力）===")
        r = post("/group/setAdmin", b_tok, {"groupId": G_ADMIN, "userId": A_ID, "role": 1})[1]
        check("群主 B 将 A 设为管理员 code=0", code_of(r) == 0, r.get("message"))
        check("A 的 role 已变为 1",
              sql1("SELECT role FROM user_contact WHERE user_id='%s' AND contact_id='%s';"
                   % (A_ID, G_ADMIN)) == "1")

        lst, total = load_apply(a_tok)
        found = find_apply(lst, c_apply, contact_type=1)
        check("★ 群管理员 A 能在申请列表看到本群入群申请", bool(found), "list=%d 条" % len(lst))
        check("管理员视角下 contactName 为群名", bool(found) and found.get("contactName") == G_NAME,
              "contactName=%s" % (found.get("contactName") if found else "-"))
        check("管理员视角分页 total 与列表同源", total is None or int(total) >= len(lst),
              "total=%s list=%d" % (total, len(lst)))

        r = post("/contact/dealWithApply", a_tok, {"applyId": c_apply, "status": 1})[1]
        check("★ 群管理员 A 审批通过 code=0", code_of(r) == 0,
              "code=%s msg=%s" % (code_of(r), r.get("message")))
        check("★ 审批通过后 C 真正入群", is_member(C_ID, G_ADMIN))

        # ── 8. 回归护栏：好友申请审批权限未被放宽 ─────────────
        out("\n=== 8. 回归护栏 好友申请审批人仍为被申请人本人 ===")
        # user_contact_apply 有 (apply_user_id,receive_user_id,contact_id) 唯一索引，先清本组申请
        sql("DELETE FROM user_contact_apply WHERE contact_id='%s';" % G_ADMIN)
        # 好友申请的 contact_id 必须等于 receive_user_id（applyAdd 对 USER 类型的既有约定：
        # receiveUserId = contactId = 被添加方）。C 与 B 当前不是好友。
        f_apply = make_apply(C_ID, B_ID, B_ID, 0)
        r = post("/contact/dealWithApply", a_tok, {"applyId": f_apply, "status": 1})[1]
        check("群管理员 A 不能处理他人好友申请 code=1001", code_of(r) == 1001,
              "code=%s msg=%s" % (code_of(r), r.get("message")))
        r = post("/contact/dealWithApply", b_tok, {"applyId": f_apply, "status": 1})[1]
        check("被申请人 B 本人可处理好友申请 code=0", code_of(r) == 0,
              "code=%s msg=%s" % (code_of(r), r.get("message")))
        check("好友申请通过后 C 与 B 互为好友",
              sql1("SELECT COUNT(*) FROM user_contact WHERE user_id='%s' AND contact_id='%s' "
                   "AND contact_type=0 AND status=1;" % (C_ID, B_ID)) == "1")
        sql("DELETE FROM user_contact WHERE (user_id='%s' AND contact_id='%s') "
            "OR (user_id='%s' AND contact_id='%s');" % (C_ID, B_ID, B_ID, C_ID))

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
