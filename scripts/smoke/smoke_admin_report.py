#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat 管理端「举报处理 / 审计」活体冒烟（stdlib only，不入库）

覆盖 engineering/qa/2026-09-26-report-admin.md 的验收：
  1 管理员分页查看全部举报 + 类型/状态/理由/时间过滤
  2 详情含被举报内容全文与双方信息
  3 处置：已处理/已驳回 + 处理人/时间/备注 + 删内容/封禁发布者
  4 每次处置写入不可变审计日志，可按要求查询
  5 非管理员被 checkAdmin 拦截（404），无 token 901；非法参数 2702/2703
  6 处置后状态分布与 fixture 全量清理（回归基线）

前置：后端 5050 已启动、migration-006 已执行、MySQL/Redis 可用。
用法：python smoke_admin_report.py [日志文件]

约定：fixture 内容均以 SMOKKE/SMOKE 开头，消息分支复用真实消息 1846；
API 断言经 JSON(utf-8) 读取，DB 断言只读数值/ASCII 字段。
"""
import sys
import json
import time
import hashlib
import subprocess
import urllib.request
import urllib.parse
import urllib.error

# Windows 控制台编码兜底（日志文件另行以 utf-8 写）
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
PWD_RAW = "Test@123456"
ADMIN_EMAIL = "test@qq.com"          # easychat.admin-emails 白名单
USER_EMAIL = "karina7710@test.com"   # 非管理员
FIX_USER = "smokeuser01"
FIX_NICK = "SMOKE-NICK-01"
CONTENT_A = "SMOKE-CONTENT-A moment to be soft-deleted"
CONTENT_B = "SMOKE-CONTENT-B moment whose publisher will be banned"
CONTENT_C = "SMOKE-CONTENT-C comment to be soft-deleted"
MSG_ID = 1846                        # 复用真实消息（只读，不封禁其发送者）

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
         "easychat", "-N", "-e", query],
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


def code_of(res):
    return res.get("code") if isinstance(res, dict) else None


def data_of(res):
    return (res or {}).get("data") or {}


def post(path, token, fields=None):
    return http_req(path, token=token, fields=fields or {})


def login(email):
    _, r = http_req("/account/checkCode", get=True)
    if code_of(r) != 0:
        raise RuntimeError("checkCode failed: %s" % r)
    key = data_of(r)["checkCodeKey"]
    p = subprocess.run([REDIS, "GET", "easychat:checkcode:" + key],
                       capture_output=True, text=True)
    code = p.stdout.strip().strip('"')
    if not code:
        raise RuntimeError("captcha empty for key=%s" % key)
    body = {"email": email,
            "password": hashlib.md5(PWD_RAW.encode()).hexdigest(),
            "checkCodeKey": key, "checkCode": code}
    _, r = http_req("/account/login", fields=body)
    if code_of(r) != 0:
        raise RuntimeError("login %s failed: %s" % (email, r))
    return data_of(r)["token"], data_of(r)


def load_reports(token, params):
    _, r = post("/admin/report/loadReport", token, params)
    if code_of(r) != 0:
        return None, None
    d = data_of(r)
    return d.get("totalCount"), d.get("list") or []


def find_report(lst, report_type, target_id):
    for it in lst:
        if it.get("reportType") == report_type and it.get("targetId") == target_id:
            return it
    return None


def counts():
    return (int(sql1("SELECT COUNT(*) FROM moment_report;") or 0),
            int(sql1("SELECT COUNT(*) FROM message_report;") or 0),
            int(sql1("SELECT COUNT(*) FROM report_audit_log;") or 0))


# ── fixture 管理 ────────────────────────────────────────────
def precleanup():
    """按内容标记清理历史 smoke fixture（避免同表子查询 1093）"""
    sql("DELETE ar FROM report_audit_log ar "
        "JOIN moment_report mr ON ar.report_id = mr.id AND ar.report_type IN (1,2) "
        "JOIN moment m ON mr.moment_id = m.id WHERE m.content LIKE 'SMOKE-%';")
    sql("DELETE ar FROM report_audit_log ar "
        "JOIN moment_report mr ON ar.report_id = mr.id AND ar.report_type IN (1,2) "
        "JOIN moment_comment mc ON mr.comment_id = mc.id WHERE mc.content LIKE 'SMOKE-%';")
    sql("DELETE ar FROM report_audit_log ar "
        "JOIN message_report ms ON ar.report_id = ms.id AND ar.report_type = 3 "
        "WHERE ms.description LIKE 'smoke-%';")
    sql("DELETE FROM moment_report WHERE moment_id IN "
        "(SELECT id FROM moment WHERE content LIKE 'SMOKE-%') "
        "OR comment_id IN (SELECT id FROM moment_comment WHERE content LIKE 'SMOKE-%');")
    sql("DELETE FROM message_report WHERE description LIKE 'smoke-%';")
    sql("DELETE FROM moment WHERE content LIKE 'SMOKE-%';")
    sql("DELETE FROM moment_comment WHERE content LIKE 'SMOKE-%';")
    sql("DELETE FROM user_info WHERE user_id='%s';" % FIX_USER)


def postcleanup():
    """跑完后清空三表 fixture 行 + fixture 内容（基线已强制为 0，三表仅含本次数据）"""
    sql("DELETE FROM report_audit_log; DELETE FROM moment_report; DELETE FROM message_report;")
    sql("DELETE FROM moment WHERE content LIKE 'SMOKE-%';")
    sql("DELETE FROM moment_comment WHERE content LIKE 'SMOKE-%';")
    sql("DELETE FROM user_info WHERE user_id='%s';" % FIX_USER)


def seed():
    now = int(time.time() * 1000)
    sql("INSERT INTO user_info (user_id,email,nick_name,password,status,join_type,sex,create_time) "
        "VALUES ('%s','smoke001@test.com','%s','%s',1,1,1,NOW());"
        % (FIX_USER, FIX_NICK, hashlib.md5(PWD_RAW.encode()).hexdigest()))
    sql("INSERT INTO moment (user_id,content,media_type,visibility,status,create_time,update_time) "
        "VALUES ('%s','%s',0,0,1,%d,%d);" % (FIX_USER, CONTENT_A, now, now))
    sql("INSERT INTO moment (user_id,content,media_type,visibility,status,create_time,update_time) "
        "VALUES ('%s','%s',0,0,1,%d,%d);" % (FIX_USER, CONTENT_B, now, now))
    ma = int(sql1("SELECT id FROM moment WHERE content='%s';" % CONTENT_A))
    mb = int(sql1("SELECT id FROM moment WHERE content='%s';" % CONTENT_B))
    sql("INSERT INTO moment_comment (moment_id,user_id,parent_id,content,status,create_time) "
        "VALUES (%d,'%s',0,'%s',1,%d);" % (ma, FIX_USER, CONTENT_C, now))
    mc = int(sql1("SELECT id FROM moment_comment WHERE content='%s';" % CONTENT_C))
    return ma, mb, mc


def main():
    out("===== report-admin 活体冒烟 =====")
    out("时间: %s" % __import__("datetime").datetime.now().isoformat(timespec="seconds"))
    out("BASE=%s  admin=%s  user=%s" % (BASE, ADMIN_EMAIL, USER_EMAIL))

    # ── 0. 前置条件 ────────────────────────────────────────
    out("\n=== 0. 前置条件 ===")
    check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
    check("migration-006 report_audit_log 表存在",
          sql1("SELECT COUNT(*) FROM information_schema.tables "
               "WHERE table_schema='easychat' AND table_name='report_audit_log';") == "1")
    check("moment_report.handle_action 字段存在",
          sql1("SELECT COUNT(*) FROM information_schema.columns "
               "WHERE table_schema='easychat' AND table_name='moment_report' "
               "AND column_name='handle_action';") == "1")
    check("message_report.handle_action 字段存在",
          sql1("SELECT COUNT(*) FROM information_schema.columns "
               "WHERE table_schema='easychat' AND table_name='message_report' "
               "AND column_name='handle_action';") == "1")
    precleanup()
    pre = counts()
    check("前置：三表清空至基线 (0,0,0)", pre == (0, 0, 0), "pre=%s" % (pre,))
    if pre != (0, 0, 0):
        out("!! 基线非空，中止（避免断言被历史数据污染）")
        return 1

    ma, mb, mc = seed()
    out("   fixture: momentA=%d(删) momentB=%d(封禁) comment=%d(删) 消息=%d(复用真实)"
        % (ma, mb, mc, MSG_ID))

    # ── 1. 登录 ────────────────────────────────────────────
    out("\n=== 1. 登录 ===")
    admin_token, admin_vo = login(ADMIN_EMAIL)
    check("admin 登录成功", bool(admin_token), "userId=%s" % admin_vo.get("userId"))
    check("admin 身份 admin=true", admin_vo.get("admin") is True, admin_vo.get("admin"))
    user_token, user_vo = login(USER_EMAIL)
    check("普通用户登录成功", bool(user_token))
    check("普通用户 admin=false", user_vo.get("admin") is False, user_vo.get("admin"))
    admin_id = admin_vo.get("userId")
    user_id = user_vo.get("userId")

    # ── 2. 用户侧举报（真实链路入口） ───────────────────────
    out("\n=== 2. 用户侧举报（karina） ===")
    _, r = post("/report/moment", user_token,
                {"momentId": ma, "reason": 4, "description": "smoke-report-A"})
    check("举报动态A成功", code_of(r) == 0, "code=%s" % code_of(r))
    _, r = post("/report/moment", user_token,
                {"momentId": ma, "reason": 4, "description": "smoke-report-A"})
    check("重复举报幂等（仍只 1 行）",
          code_of(r) == 0 and sql1("SELECT COUNT(*) FROM moment_report WHERE moment_id=%d;" % ma) == "1")
    _, r = post("/report/moment", user_token,
                {"commentId": mc, "reason": 0, "description": "smoke-report-C"})
    check("举报评论成功", code_of(r) == 0, "code=%s" % code_of(r))
    _, r = post("/report/moment", user_token,
                {"momentId": mb, "reason": 1, "description": "smoke-report-B"})
    check("举报动态B成功", code_of(r) == 0, "code=%s" % code_of(r))
    _, r = post("/report/chat", user_token,
                {"messageId": MSG_ID, "reason": 2, "description": "smoke-report-msg"})
    check("举报消息1846成功（复用真实消息）", code_of(r) == 0, "code=%s" % code_of(r))
    check("落库：moment_report=3 / message_report=1",
          sql1("SELECT COUNT(*) FROM moment_report;") == "3" and
          sql1("SELECT COUNT(*) FROM message_report;") == "1")

    # ── 3. 管理端列表 + 过滤 ────────────────────────────────
    out("\n=== 3. 管理端列表与过滤 ===")
    total, lst = load_reports(admin_token, {"pageNo": 1, "pageSize": 20})
    check("列表可见全部 4 条举报", total == 4, "totalCount=%s" % total)
    ra = find_report(lst, 1, ma)
    rb = find_report(lst, 1, mb)
    rc = find_report(lst, 2, mc)
    rm = find_report(lst, 3, MSG_ID)
    check("三条举报可按 reportType/targetId 定位", all([ra, rb, rc, rm]))
    check("动态/评论摘要含内容（contentExcerpt）",
          all("SMOKE-CONTENT" in (x.get("contentExcerpt") or "") for x in (ra, rb, rc) if x) if all([ra, rb, rc]) else False,
          "ra=%r rb=%r rc=%r" % (ra and ra.get("contentExcerpt"), rb and rb.get("contentExcerpt"),
                                 rc and rc.get("contentExcerpt")))
    check("消息摘要非空（真实消息全文）",
          bool(rm and (rm.get("contentExcerpt") or "").strip()), rm and rm.get("contentExcerpt"))
    check("举报人昵称已联表（reportUserName 非空）",
          all((x.get("reportUserName") or "").strip() for x in (ra, rb, rc, rm) if x) if all([ra, rb, rc, rm]) else False,
          "ra=%s" % (ra and ra.get("reportUserName")))
    check("列表初始 status 均为 0 待处理",
          all(x.get("status") == 0 for x in (ra, rb, rc, rm) if x) if all([ra, rb, rc, rm]) else False)

    _, r = post("/admin/report/loadReport", admin_token, {"reportType": 1})
    check("过滤 reportType=1 → 2 条", data_of(r).get("totalCount") == 2, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"reportType": 2})
    check("过滤 reportType=2 → 1 条", data_of(r).get("totalCount") == 1, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"reportType": 3})
    check("过滤 reportType=3 → 1 条", data_of(r).get("totalCount") == 1, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"status": 0})
    check("过滤 status=0 待处理 → 4 条", data_of(r).get("totalCount") == 4, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"reason": 4})
    check("过滤 reason=4 → 1 条", data_of(r).get("totalCount") == 1, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"reason": 2})
    check("过滤 reason=2 → 1 条", data_of(r).get("totalCount") == 1, data_of(r).get("totalCount"))
    now_ms = int(time.time() * 1000)
    _, r = post("/admin/report/loadReport", admin_token,
                {"startTime": now_ms - 3600000, "endTime": now_ms + 3600000})
    check("时间窗（当前±1h）→ 4 条", data_of(r).get("totalCount") == 4, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"endTime": now_ms - 30 * 86400000})
    check("时间窗外（30 天前）→ 0 条", data_of(r).get("totalCount") == 0, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"pageNo": 2, "pageSize": 3})
    check("分页 pageNo=2/size=3 → 1 条",
          data_of(r).get("totalCount") == 4 and len(data_of(r).get("list") or []) == 1,
          "total=%s len=%s" % (data_of(r).get("totalCount"), len(data_of(r).get("list") or [])))

    # ── 4. 详情 ────────────────────────────────────────────
    out("\n=== 4. 举报详情 ===")
    _, r = post("/admin/report/getReportDetail", admin_token, {"id": ra["id"], "reportType": 1})
    d = data_of(r)
    check("动态A详情成功", code_of(r) == 0, "code=%s" % code_of(r))
    check("详情含内容全文", d.get("content") == CONTENT_A, repr(d.get("content")))
    check("详情含发布者信息",
          d.get("publisherId") == FIX_USER and d.get("publisherNickName") == FIX_NICK,
          "%s/%s" % (d.get("publisherId"), d.get("publisherNickName")))
    check("详情含举报人信息",
          d.get("reportUserId") == user_id and bool(d.get("reporterNickName")),
          "%s/%s" % (d.get("reportUserId"), d.get("reporterNickName")))
    check("详情初始 status=0", d.get("status") == 0, d.get("status"))

    _, r = post("/admin/report/getReportDetail", admin_token, {"id": rc["id"], "reportType": 2})
    d = data_of(r)
    check("评论详情含内容全文", code_of(r) == 0 and d.get("content") == CONTENT_C, repr(d.get("content")))

    _, r = post("/admin/report/getReportDetail", admin_token, {"id": rm["id"], "reportType": 3})
    d = data_of(r)
    check("消息详情含内容与发送者",
          code_of(r) == 0 and bool(d.get("content")) and bool(d.get("publisherId")),
          "%s/%s" % (d.get("content"), d.get("publisherId")))

    # ── 5. 负向：权限与非法参数 ─────────────────────────────
    out("\n=== 5. 负向用例 ===")
    _, r = post("/admin/report/loadReport", None, {})
    check("无 token → 901 登录超时", code_of(r) == 901, code_of(r))
    _, r = post("/admin/report/loadReport", user_token, {})
    check("非管理员 loadReport → 404", code_of(r) == 404, code_of(r))
    _, r = post("/admin/report/dealReport", user_token,
                {"id": ra["id"], "reportType": 1, "status": 1, "handleAction": 0})
    check("非管理员 dealReport → 404", code_of(r) == 404, code_of(r))
    _, r = post("/admin/report/getReportDetail", admin_token, {"id": 999999999, "reportType": 1})
    check("详情不存在 → 2702", code_of(r) == 2702, code_of(r))
    _, r = post("/admin/report/dealReport", admin_token,
                {"id": ra["id"], "reportType": 1, "status": 1, "handleAction": 9})
    check("非法 handleAction=9 → 2703", code_of(r) == 2703, code_of(r))
    _, r = post("/admin/report/dealReport", admin_token,
                {"id": ra["id"], "reportType": 1, "status": 3, "handleAction": 0})
    check("非法 status=3 → 2703", code_of(r) == 2703, code_of(r))
    check("非法参数未改状态（仍 status=0）",
          sql1("SELECT status FROM moment_report WHERE moment_id=%d;" % ma) == "0")

    # ── 6. 处置（4 条全覆盖） ───────────────────────────────
    out("\n=== 6. 处置 ===")
    _, r = post("/admin/report/dealReport", admin_token,
                {"id": ra["id"], "reportType": 1, "status": 1, "handleAction": 1,
                 "handleNote": "smoke-delete-moment"})
    check("处置A：已处理+删内容", code_of(r) == 0, "code=%s" % code_of(r))
    _, r = post("/admin/report/dealReport", admin_token,
                {"id": rc["id"], "reportType": 2, "status": 1, "handleAction": 1,
                 "handleNote": "smoke-delete-comment"})
    check("处置评论：已处理+删内容", code_of(r) == 0, "code=%s" % code_of(r))
    _, r = post("/admin/report/dealReport", admin_token,
                {"id": rb["id"], "reportType": 1, "status": 1, "handleAction": 2,
                 "handleNote": "smoke-ban-publisher"})
    check("处置B：已处理+封禁发布者", code_of(r) == 0, "code=%s" % code_of(r))
    _, r = post("/admin/report/dealReport", admin_token,
                {"id": rm["id"], "reportType": 3, "status": 2, "handleAction": 0,
                 "handleNote": "smoke-reject-message"})
    check("处置消息：已驳回+仅记录", code_of(r) == 0, "code=%s" % code_of(r))

    check("DB 动态A已软删 status=0", sql1("SELECT status FROM moment WHERE id=%d;" % ma) == "0")
    check("DB 评论已软删 status=0", sql1("SELECT status FROM moment_comment WHERE id=%d;" % mc) == "0")
    check("DB 动态B未删 status=1（封禁不动内容）",
          sql1("SELECT status FROM moment WHERE id=%d;" % mb) == "1")
    check("DB 发布者已封禁 status=0",
          sql1("SELECT status FROM user_info WHERE user_id='%s';" % FIX_USER) == "0")
    check("DB 消息1846未被物理删除",
          sql1("SELECT COUNT(*) FROM chat_message WHERE message_id=%d;" % MSG_ID) == "1")
    check("DB 举报行已记 handle_user_id/handle_note/handle_action",
          sql1("SELECT handle_user_id FROM moment_report WHERE moment_id=%d;" % ma) == admin_id
          and sql1("SELECT handle_note FROM moment_report WHERE moment_id=%d;" % ma) == "smoke-delete-moment"
          and sql1("SELECT handle_action FROM moment_report WHERE moment_id=%d;" % ma) == "1",
          "user=%s note=%s action=%s" % (sql1("SELECT handle_user_id FROM moment_report WHERE moment_id=%d;" % ma),
                                         sql1("SELECT handle_note FROM moment_report WHERE moment_id=%d;" % ma),
                                         sql1("SELECT handle_action FROM moment_report WHERE moment_id=%d;" % ma)))
    check("DB handle_time 非空",
          sql1("SELECT handle_time FROM moment_report WHERE moment_id=%d;" % ma) not in ("", "NULL"))
    check("DB 消息举报行 status=2/handle_action=0",
          sql1("SELECT status FROM message_report WHERE message_id=%d;" % MSG_ID) == "2"
          and sql1("SELECT handle_action FROM message_report WHERE message_id=%d;" % MSG_ID) == "0")

    _, r = post("/admin/report/dealReport", admin_token,
                {"id": ra["id"], "reportType": 1, "status": 1, "handleAction": 1})
    check("重复处置已处理记录 → 2702", code_of(r) == 2702, code_of(r))

    # ── 7. 审计日志 ─────────────────────────────────────────
    out("\n=== 7. 审计日志 ===")
    _, r = post("/admin/report/loadAuditLog", admin_token, {"pageNo": 1, "pageSize": 20})
    check("审计共 4 条（每次处置一条）", data_of(r).get("totalCount") == 4, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadAuditLog", admin_token, {"action": 1})
    check("过滤 action=1 已处理 → 3 条", data_of(r).get("totalCount") == 3, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadAuditLog", admin_token, {"action": 2})
    check("过滤 action=2 已驳回 → 1 条", data_of(r).get("totalCount") == 1, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadAuditLog", admin_token, {"adminId": admin_id})
    check("过滤 adminId → 4 条", data_of(r).get("totalCount") == 4, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadAuditLog", admin_token,
                {"reportId": ra["id"], "reportType": 1})
    d = data_of(r)
    a0 = (d.get("list") or [{}])[0]
    check("按 reportId+type 查询 → 1 条且字段齐全",
          d.get("totalCount") == 1 and a0.get("adminId") == admin_id
          and a0.get("action") == 1 and a0.get("handleAction") == 1
          and a0.get("handleNote") == "smoke-delete-moment"
          and a0.get("targetId") == ma and bool(a0.get("createTime")),
          json.dumps(a0, ensure_ascii=False)[:260])
    _, r = post("/admin/report/loadAuditLog", admin_token, {"reportId": rm["id"]})
    check("消息处置审计存在（已驳回）",
          data_of(r).get("totalCount") == 1 and (data_of(r).get("list") or [{}])[0].get("action") == 2)
    _, r = post("/admin/report/loadAuditLog", admin_token, {"pageNo": 2, "pageSize": 3})
    check("审计分页 pageNo=2/size=3 → 1 条",
          len(data_of(r).get("list") or []) == 1 and data_of(r).get("totalCount") == 4,
          "total=%s len=%s" % (data_of(r).get("totalCount"), len(data_of(r).get("list") or [])))

    # ── 8. 处置后列表状态分布 ───────────────────────────────
    out("\n=== 8. 处置后状态分布 ===")
    _, r = post("/admin/report/loadReport", admin_token, {"status": 0})
    check("status=0 待处理 → 0 条", data_of(r).get("totalCount") == 0, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"status": 1})
    check("status=1 已处理 → 3 条", data_of(r).get("totalCount") == 3, data_of(r).get("totalCount"))
    _, r = post("/admin/report/loadReport", admin_token, {"status": 2})
    check("status=2 已驳回 → 1 条", data_of(r).get("totalCount") == 1, data_of(r).get("totalCount"))

    # ── 9. 清理 + 回归基线 ──────────────────────────────────
    out("\n=== 9. 清理 fixture ===")
    postcleanup()
    post_c = counts()
    check("清理后三表回基线 (0,0,0)", post_c == (0, 0, 0), "post=%s" % (post_c,))
    check("清理后 fixture 用户/内容已删",
          sql1("SELECT COUNT(*) FROM user_info WHERE user_id='%s';" % FIX_USER) == "0"
          and sql1("SELECT COUNT(*) FROM moment WHERE content LIKE 'SMOKE-%';") == "0"
          and sql1("SELECT COUNT(*) FROM moment_comment WHERE content LIKE 'SMOKE-%';") == "0")
    check("真实消息1846仍在且状态未变",
          sql1("SELECT status FROM chat_message WHERE message_id=%d;" % MSG_ID) == "1")

    # ── 汇总 ────────────────────────────────────────────────
    failed = [n for n, ok in RESULTS if not ok]
    out("\n===== SUMMARY =====")
    out("用例 %d 项 / 通过 %d / 失败 %d" % (len(RESULTS), len(RESULTS) - len(failed), len(failed)))
    for n in failed:
        out("  FAIL: %s" % n)
    out("SMOKE %s" % ("PASS" if not failed else "FAIL"))
    return 0 if not failed else 1


if __name__ == "__main__":
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")
    try:
        rc = main()
    except Exception as e:
        out("\n[EXCEPTION] %s" % e)
        import traceback
        out(traceback.format_exc())
        rc = 2
    finally:
        if _LOGFP:
            _LOGFP.close()
    sys.exit(rc)
