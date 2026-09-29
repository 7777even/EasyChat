#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat 管理端消息删除位活体冒烟（stdlib only）

覆盖 openspec/changes/2026-09-29-admin-message-delete/tasks.md 阶段一/四：
  P0 migration-009：chat_message.delete_flag 列存在、BIGINT NOT NULL DEFAULT 0、
     存量全量存活（WHERE delete_flag=0 返回全量）
  P1 单聊删除链：造消息→双人举报→处置删除→delete_flag 置位；接收方/发送方离线
     队列 20 帧（规则①：发送方副本 contactId=会话对方；接收方副本 contactId=发送者）、
     非会话方不推送；服务端预览占位；用户侧 loadHistory/search/globalSearch 不可见；
     管理端详情+PK 行证据保留；recall/download/locate 三路 2201（HTTP 400）
  P2 幂等：同一消息第二份待处理举报再处置 → 不改写不重推，handleNote 含「幂等跳过」
  P3 群聊删除：群离线成员（含发送者成员）逐个入离线缓冲且 contactId=群ID（规则②）、
     群会话预览占位
  P4 分支回归：handleAction=0 仅记录（delete_flag 不动）、=2 封禁发布者（还原）、
     朋友圈类处置（还原）
  既有单聊普通消息离线队列补推 JSON 不受两规则影响（P1 基线断言）

前置：后端 5050 已启动（含阶段二代码）、MySQL/Redis 可用、无 Electron 在线。
用法：python smoke_admin_msg_delete.py [日志文件]
清理：finally 回滚 delete_flag / 会话预览 / 举报与审计 fixture 行 / 封禁状态 / 离线队列
"""
import hashlib
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
BASE = "http://localhost:5050/api"
PWD_RAW = "Test@123456"

ADMIN_EMAIL = "test@qq.com"
USER_EMAIL = "karina7710@test.com"

ADMIN_U = "U69630787860"
KARINA = "U04259455805"
PEER = "U29953535216"          # karina 的既有单聊会话对方（离线）
MEMBER2 = "U07346173613"       # 群内非发送者成员（离线）
GROUP = "G08427252986"
GROUP_SESSION = "9f8083e4c6f0a78385ab45e09a65f384"
SID_SINGLE = "380571aaeed37621c53433dcbd238254"
TOMBSTONE = "该消息已被管理员删除"
OFFLINE_PREFIX = "easychat:ws:offline:"
QUEUE_USERS = [KARINA, ADMIN_U, PEER, MEMBER2]

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
        with urllib.request.urlopen(req, timeout=20) as r:
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


def wait_server(tries=40):
    for i in range(tries):
        try:
            _, r = http_req("/account/checkCode", get=True)
            if code_of(r) == 0:
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def del_offline(*user_ids):
    ids = user_ids or QUEUE_USERS
    keys = [OFFLINE_PREFIX + u for u in ids]
    subprocess.run([REDIS, "DEL"] + keys, capture_output=True)


def queue_frames(user_id):
    p = subprocess.run([REDIS, "--raw", "LRANGE", OFFLINE_PREFIX + user_id, "0", "-1"],
                       capture_output=True)
    if p.stderr:
        out("   [WARN] redis-cli stderr: %s" % p.stderr.decode("utf-8", "replace")[:200])
    txt = p.stdout.decode("utf-8", "replace")
    frames = []
    for line in txt.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            v = json.loads(line)
            # 值经 RedisSerializer.json() 双编码：外层是 JSON 字符串，需二次解码
            if isinstance(v, str):
                v = json.loads(v)
            frames.append(v if isinstance(v, dict) else {"_decoded": v})
        except ValueError:
            frames.append({"_raw": line})
    return frames


def frames_of_type(user_id, msg_type):
    return [f for f in queue_frames(user_id) if f.get("messageType") == msg_type]


def wait_frames(user_id, msg_type, timeout=6):
    """轮询等待某类型帧入队（防推送与读取的毫秒级竞态）"""
    end = time.time() + timeout
    frames = []
    while True:
        frames = frames_of_type(user_id, msg_type)
        if frames or time.time() >= end:
            return frames
        time.sleep(0.5)


def esc(v):
    """SQL 字符串字面量转义"""
    return str(v).replace("\\", "\\\\").replace("'", "''")


def new_state():
    return {"deal_ids": [], "fixture_msgs": [], "restores": {}, "moment_report_id": None}


def run_scenarios(state, adm_tok, usr_tok, run_tag):
    # ── 捕获需回滚的现状 ──
    state["restores"]["single_last"] = sql1(
        "SELECT last_message FROM chat_session WHERE session_id='%s';" % SID_SINGLE)
    state["restores"]["group_last"] = sql1(
        "SELECT last_message FROM chat_session WHERE session_id='%s';" % GROUP_SESSION)
    state["restores"]["karina_status"] = sql1(
        "SELECT status FROM user_info WHERE user_id='%s';" % KARINA)

    k1 = "SMKDEL单聊标记%s" % run_tag
    k3 = "SMKDEL群聊标记%s" % run_tag

    # ══════ P1 单聊删除链 ══════
    out("===== P1 单聊删除链 =====")
    del_offline()

    # P1-1 造单聊消息（karina → peer，双方离线）
    _, r = post("/chat/sendMessage", usr_tok, {
        "contactId": PEER, "messageContent": k1, "messageType": 2})
    check("P1-1 发送单聊消息", code_of(r) == 0, r)
    m1 = data_of(r).get("messageId")
    sid = data_of(r).get("sessionId")
    state["fixture_msgs"].append(m1)
    check("P1-1 sessionId 为既有会话", sid == SID_SINGLE, "got=%s" % sid)

    # P1-2 基线：普通消息离线补推 JSON（已转换，不受规则①②影响）
    peer_frames = wait_frames(PEER, 2)
    base_ok = any(f.get("messageType") == 2 and f.get("contactId") == KARINA
                  and f.get("sendUserId") == KARINA for f in peer_frames)
    check("P1-2 普通消息离线队列 contactId=发送者（转换不受影响）", base_ok,
          "frames=%s" % json.dumps(peer_frames, ensure_ascii=False)[:300])

    del_offline()  # 清场，让删除帧断言干净

    # P1-3 双人举报（第二份留作 P2 幂等）
    _, r = post("/report/chat", usr_tok, {"messageId": m1, "reason": 1, "description": "冒烟"})
    check("P1-3 举报人 karina 举报", code_of(r) == 0, r)
    _, r = post("/report/chat", adm_tok, {"messageId": m1, "reason": 1, "description": "冒烟"})
    check("P1-3 举报人 admin 举报（同一消息两份待处理）", code_of(r) == 0, r)
    rids = [int(x) for x in sql(
        "SELECT id FROM message_report WHERE message_id=%d ORDER BY id;" % m1).splitlines() if x]
    check("P1-3 两份举报落库", len(rids) == 2, "ids=%s" % rids)
    if len(rids) != 2:
        return
    r1, r2 = rids
    state["deal_ids"].extend([r1, r2])

    # P1-4 处置删除（第一份）
    _, r = post("/admin/report/dealReport", adm_tok, {
        "id": r1, "reportType": 3, "status": 1, "handleAction": 1,
        "handleNote": "冒烟：逻辑删除"})
    check("P1-4 处置删除 code=0", code_of(r) == 0, r)

    # P1-5 delete_flag 置位
    df1 = sql1("SELECT delete_flag FROM chat_message WHERE message_id=%d;" % m1)
    check("P1-5 delete_flag 置位(>0)", df1.isdigit() and int(df1) > 0, "delete_flag=%s" % df1)
    state["restores"]["m1_flag"] = df1

    # P1-6 规则①：发送方离线 → 其队列 20 帧 contactId=会话对方
    f20_sender = wait_frames(KARINA, 20)
    ok_r1 = any(f.get("contactId") == PEER and f.get("sendUserId") == KARINA for f in f20_sender)
    check("P1-6 发送方离线队列 20 帧 contactId=会话对方（规则①）", ok_r1,
          "frames=%s" % json.dumps(f20_sender, ensure_ascii=False)[:300])

    # P1-6b 接收方副本 contactId=发送者（单聊转换），非会话方不推送
    f20_recv = wait_frames(PEER, 20)
    check("P1-6b 接收方 20 帧 contactId=发送者",
          any(f.get("contactId") == KARINA for f in f20_recv),
          "frames=%s" % json.dumps(f20_recv, ensure_ascii=False)[:300])
    check("P1-6b 非会话方(admin)不入 20 帧", len(frames_of_type(ADMIN_U, 20)) == 0)

    # P1-7 服务端预览占位（ADR-004：被删消息为会话最新）
    last_msg = sql1("SELECT last_message FROM chat_session WHERE session_id='%s';" % SID_SINGLE)
    check("P1-7 单聊会话预览=墓碑文案", last_msg == TOMBSTONE, "got=%r" % last_msg)

    # P1-8 用户侧三查询不可见（karina 视角）
    _, r = post("/chat/loadHistoryMessage", usr_tok,
                {"sessionId": SID_SINGLE, "pageSize": 50})
    hist = data_of(r).get("list") or []
    check("P1-8 loadHistoryMessage 不可见", code_of(r) == 0 and
          not any(int(m.get("messageId") or 0) == m1 for m in hist),
          "code=%s n=%d" % (code_of(r), len(hist)))
    _, r = post("/chat/searchMessage", usr_tok,
                {"sessionId": SID_SINGLE, "keyword": k1, "pageNo": 1})
    total = data_of(r).get("totalCount")
    check("P1-8 searchMessage 关键词 0 命中", code_of(r) == 0 and str(total) == "0",
          "code=%s total=%s" % (code_of(r), total))
    _, r = post("/chat/globalSearch", usr_tok, {"keyword": k1, "scope": "message"})
    blob = json.dumps(data_of(r), ensure_ascii=False)
    check("P1-8 globalSearch 不含原文/该消息", code_of(r) == 0 and k1 not in blob and
          str(m1) not in blob, "code=%s" % code_of(r))

    # P1-9 管理端证据保留（详情 + PK 行原文）
    _, r = post("/admin/report/getReportDetail", adm_tok, {"id": r1, "reportType": 3})
    detail = data_of(r)
    check("P1-9 举报详情 content 保留原文",
          code_of(r) == 0 and k1 in (detail.get("content") or ""),
          "content=%r" % (detail.get("content") or "")[:80])
    pk_content = sql1("SELECT message_content FROM chat_message WHERE message_id=%d;" % m1)
    check("P1-9 PK 行原文保留（管理端证据链）", k1 in pk_content)

    # P1-10 三路 2201 守卫（HTTP 400 + body 2201）
    st, r = post("/chat/locateMessage", usr_tok, {"messageId": m1})
    check("P1-10 locateMessage → 400/2201", st == 400 and code_of(r) == 2201, "st=%s r=%s" % (st, r))
    st, r = post("/chat/recallMessage", usr_tok, {"messageId": m1})
    check("P1-10 recallMessage → 400/2201", st == 400 and code_of(r) == 2201, "st=%s r=%s" % (st, r))
    st, r = post("/chat/downloadFile", usr_tok, {"fileId": m1, "showCover": "false"})
    check("P1-10 downloadFile → 400/2201", st == 400 and code_of(r) == 2201, "st=%s r=%s" % (st, r))

    # ══════ P2 幂等 ══════
    out("===== P2 重复处置幂等 =====")
    sender_cnt_before = len(frames_of_type(KARINA, 20))
    _, r = post("/admin/report/dealReport", adm_tok, {
        "id": r2, "reportType": 3, "status": 1, "handleAction": 1,
        "handleNote": "冒烟：幂等处置"})
    check("P2-1 第二份处置 code=0", code_of(r) == 0, r)
    df1_again = sql1("SELECT delete_flag FROM chat_message WHERE message_id=%d;" % m1)
    check("P2-2 delete_flag 不改写（幂等）", df1_again == df1, "before=%s after=%s" % (df1, df1_again))
    sender_cnt_after = len(frames_of_type(KARINA, 20))
    check("P2-3 不重推 20 帧", sender_cnt_after == sender_cnt_before,
          "before=%d after=%d" % (sender_cnt_before, sender_cnt_after))
    _, r = post("/admin/report/getReportDetail", adm_tok, {"id": r2, "reportType": 3})
    note = (data_of(r).get("handleNote") or "")
    check("P2-4 handleNote 含「幂等跳过」", "幂等跳过" in note, "note=%r" % note)

    # ══════ P3 群聊删除 ══════
    out("===== P3 群聊删除链 =====")
    _, r = post("/chat/sendMessage", adm_tok, {
        "contactId": GROUP, "messageContent": k3, "messageType": 2})
    check("P3-1 发送群消息", code_of(r) == 0, r)
    g1 = data_of(r).get("messageId")
    gsid = data_of(r).get("sessionId")
    state["fixture_msgs"].append(g1)
    check("P3-1 群 sessionId 正确", gsid == GROUP_SESSION, "got=%s" % gsid)
    del_offline(KARINA, ADMIN_U, PEER, MEMBER2)  # 清场（群发送不入离线队列，此处保险）

    _, r = post("/report/chat", usr_tok, {"messageId": g1, "reason": 1, "description": "冒烟"})
    check("P3-2 举报群消息", code_of(r) == 0, r)
    r3 = int(sql1("SELECT id FROM message_report WHERE message_id=%d ORDER BY id DESC LIMIT 1;" % g1))
    state["deal_ids"].append(r3)
    _, r = post("/admin/report/dealReport", adm_tok, {
        "id": r3, "reportType": 3, "status": 1, "handleAction": 1,
        "handleNote": "冒烟：群消息删除"})
    check("P3-3 处置删除群消息", code_of(r) == 0, r)
    gdf = sql1("SELECT delete_flag FROM chat_message WHERE message_id=%d;" % g1)
    check("P3-4 群消息 delete_flag 置位", gdf.isdigit() and int(gdf) > 0, "got=%s" % gdf)
    state["restores"]["g1_flag"] = gdf

    # 规则②：所有离线群成员（含发送者本人）队列 20 帧 contactId=群ID（未转换）
    f20_admin = wait_frames(ADMIN_U, 20)
    f20_m2 = wait_frames(MEMBER2, 20)
    check("P3-5 发送者(成员)离线 20 帧 contactId=groupId",
          any(f.get("contactId") == GROUP for f in f20_admin),
          "frames=%s" % json.dumps(f20_admin, ensure_ascii=False)[:300])
    check("P3-5 群离线成员 20 帧 contactId=groupId（规则②）",
          any(f.get("contactId") == GROUP for f in f20_m2),
          "frames=%s" % json.dumps(f20_m2, ensure_ascii=False)[:300])
    glast = sql1("SELECT last_message FROM chat_session WHERE session_id='%s';" % GROUP_SESSION)
    check("P3-6 群会话预览=墓碑文案", glast == TOMBSTONE, "got=%r" % glast)

    # ══════ P4 其余分支回归 ══════
    out("===== P4 dealReport 分支回归 =====")

    # P4-1 handleAction=0 仅记录
    _, r = post("/chat/sendMessage", usr_tok, {
        "contactId": PEER, "messageContent": "SMKDEL仅记录%s" % run_tag, "messageType": 2})
    m2 = data_of(r).get("messageId")
    state["fixture_msgs"].append(m2)
    _, r = post("/report/chat", adm_tok, {"messageId": m2, "reason": 1, "description": "冒烟"})
    check("P4-1a 举报（仅记录用）", code_of(r) == 0, r)
    rm2 = int(sql1("SELECT id FROM message_report WHERE message_id=%d ORDER BY id DESC LIMIT 1;" % m2))
    state["deal_ids"].append(rm2)
    _, r = post("/admin/report/dealReport", adm_tok, {
        "id": rm2, "reportType": 3, "status": 1, "handleAction": 0,
        "handleNote": "冒烟：仅记录"})
    m2df = sql1("SELECT delete_flag FROM chat_message WHERE message_id=%d;" % m2)
    check("P4-1 处置 code=0 且 delete_flag 不动", code_of(r) == 0 and m2df == "0",
          "code=%s delete_flag=%s" % (code_of(r), m2df))

    # P4-2 朋友圈类分支（造举报→处置删除→还原 status）
    moment_row = sql1("SELECT CONCAT(id,'#',IFNULL(status,-1)) FROM moment "
                      "WHERE status=1 ORDER BY id DESC LIMIT 1;")
    if moment_row and "#" in moment_row:
        mid, mstatus = moment_row.split("#")
        state["restores"]["moment"] = (mid, mstatus)
        _, r = post("/report/moment", usr_tok, {"momentId": mid, "reason": 1, "description": "冒烟"})
        check("P4-2a 举报朋友圈动态", code_of(r) == 0, r)
        rm = int(sql1("SELECT id FROM moment_report WHERE moment_id=%s AND status=0 "
                      "ORDER BY id DESC LIMIT 1;" % mid))
        state["deal_ids"].append(rm)
        state["moment_report_id"] = rm
        _, r = post("/admin/report/dealReport", adm_tok, {
            "id": rm, "reportType": 1, "status": 1, "handleAction": 1,
            "handleNote": "冒烟：朋友圈处置"})
        mstat = sql1("SELECT status FROM moment WHERE id=%s;" % mid)
        check("P4-2 朋友圈处置 code=0 且动态 status=0",
              code_of(r) == 0 and mstat == "0", "code=%s status=%s" % (code_of(r), mstat))
    else:
        check("P4-2 朋友圈动态 fixture 存在", False, "query=%r" % moment_row)

    # P4-3 handleAction=2 封禁发布者（karina 为发送者→封禁→立即还原）
    _, r = post("/chat/sendMessage", usr_tok, {
        "contactId": PEER, "messageContent": "SMKDEL封禁用%s" % run_tag, "messageType": 2})
    m3 = data_of(r).get("messageId")
    state["fixture_msgs"].append(m3)
    _, r = post("/report/chat", adm_tok, {"messageId": m3, "reason": 1, "description": "冒烟"})
    check("P4-3a 举报（封禁用）", code_of(r) == 0, r)
    rm3 = int(sql1("SELECT id FROM message_report WHERE message_id=%d ORDER BY id DESC LIMIT 1;" % m3))
    state["deal_ids"].append(rm3)
    _, r = post("/admin/report/dealReport", adm_tok, {
        "id": rm3, "reportType": 3, "status": 1, "handleAction": 2,
        "handleNote": "冒烟：封禁发布者"})
    kstat = sql1("SELECT status FROM user_info WHERE user_id='%s';" % KARINA)
    check("P4-3 封禁发布者 code=0 且 karina status=0",
          code_of(r) == 0 and kstat == "0", "code=%s status=%s" % (code_of(r), kstat))


def cleanup(state):
    """回滚全部 fixture 副作用（尽力而为，失败不阻断汇总）"""
    out("===== CLEANUP 回滚 fixture =====")
    restores = state.get("restores", {})
    try:
        ids = [m for m in state.get("fixture_msgs", []) if m]
        if ids:
            sql("UPDATE chat_message SET delete_flag=0 WHERE message_id IN (%s);"
                % ",".join(str(int(m)) for m in ids))
            out("   已还原 delete_flag：%s" % ids)
    except Exception as e:
        out("   [WARN] delete_flag 还原失败: %s" % e)
    try:
        for key, session_id in (("single_last", SID_SINGLE), ("group_last", GROUP_SESSION)):
            if key in restores:
                prev = restores[key]
                val = "NULL" if prev == "" else "'%s'" % esc(prev)
                sql("UPDATE chat_session SET last_message=%s WHERE session_id='%s';"
                    % (val, session_id))
                out("   已还原会话预览 %s" % session_id)
    except Exception as e:
        out("   [WARN] 会话预览还原失败: %s" % e)
    try:
        ids = [m for m in state.get("fixture_msgs", []) if m]
        if ids:
            sql("DELETE FROM message_report WHERE message_id IN (%s);"
                % ",".join(str(int(m)) for m in ids))
            out("   已删除 message_report fixture")
    except Exception as e:
        out("   [WARN] message_report 清理失败: %s" % e)
    try:
        deal_ids = [int(x) for x in state.get("deal_ids", []) if x]
        if deal_ids:
            sql("DELETE FROM report_audit_log WHERE report_id IN (%s);"
                % ",".join(str(x) for x in deal_ids))
            out("   已删除 report_audit_log fixture")
    except Exception as e:
        out("   [WARN] 审计日志清理失败: %s" % e)
    try:
        if state.get("moment_report_id"):
            sql("DELETE FROM moment_report WHERE id=%d;" % int(state["moment_report_id"]))
            out("   已删除 moment_report fixture")
    except Exception as e:
        out("   [WARN] moment_report 清理失败: %s" % e)
    try:
        if "moment" in restores:
            mid, mstatus = restores["moment"]
            sql("UPDATE moment SET status=%s WHERE id=%s;" % (mstatus, mid))
            out("   已还原 moment status id=%s" % mid)
    except Exception as e:
        out("   [WARN] moment 还原失败: %s" % e)
    try:
        kstat = restores.get("karina_status")
        cur = sql1("SELECT status FROM user_info WHERE user_id='%s';" % KARINA)
        if kstat and cur != kstat:
            sql("UPDATE user_info SET status='%s' WHERE user_id='%s';" % (kstat, KARINA))
            out("   已还原 karina status=%s" % kstat)
    except Exception as e:
        out("   [WARN] 用户状态还原失败: %s" % e)
    del_offline()
    out("   已清空离线队列 %s" % QUEUE_USERS)


def main():
    out("===== admin-message-delete 活体冒烟（P0–P4）=====")
    out("时间: %s" % __import__("datetime").datetime.now().isoformat(timespec="seconds"))

    # ── P0 migration-009 断言 ──
    col = sql1(
        "SELECT CONCAT(DATA_TYPE,'#',IS_NULLABLE,'#',IFNULL(COLUMN_DEFAULT,'NULL')) "
        "FROM information_schema.columns "
        "WHERE table_schema='easychat' AND table_name='chat_message' AND COLUMN_NAME='delete_flag';")
    check("P0-1 chat_message.delete_flag 列存在", bool(col) and col != "NULL", "got=%r" % col)
    check("P0-1 类型 bigint NOT NULL DEFAULT 0", col == "bigint#NO#0", "got=%r" % col)
    total = sql1("SELECT COUNT(*) FROM chat_message;")
    alive = sql1("SELECT COUNT(*) FROM chat_message WHERE delete_flag = 0;")
    marked = sql1("SELECT COUNT(*) FROM chat_message WHERE delete_flag <> 0;")
    check("P0-2 WHERE delete_flag=0 返回全量存量", total == alive,
          "total=%s alive=%s" % (total, alive))
    check("P0-2 存量无被删行（delete_flag<>0 为 0 行）", marked == "0", "marked=%s" % marked)

    if not wait_server():
        check("后端 5050 可达", False, "等待 120s 超时")
        return
    check("后端 5050 可达", True)

    del_offline()
    adm_tok, _ = login(ADMIN_EMAIL)
    usr_tok, _ = login(USER_EMAIL)
    out("登录成功：admin=%s / user=%s" % (ADMIN_U, KARINA))

    state = new_state()
    try:
        run_scenarios(state, adm_tok, usr_tok, str(int(time.time()))[-6:])
    finally:
        cleanup(state)

    failed = [n for n, ok in RESULTS if not ok]
    out("===== SUMMARY =====")
    out("用例 %d 项 / 通过 %d / 失败 %d" % (len(RESULTS), len(RESULTS) - len(failed), len(failed)))
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
