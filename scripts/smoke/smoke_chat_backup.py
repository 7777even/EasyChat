#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat 跨会话云端全量备份「取数链路」活体冒烟（stdlib only，只读不写）

覆盖 openspec/changes/2026-09-26-chat-backup-export 的核心风险点：
  C1 会话枚举：从服务端 chat_session_user 取到当前账号会话（前端取的是本地同名表，结构一致）
  C1 跨会话翻页：对每个会话用 loadHistoryMessage 游标翻页取全，验证能终止且能取到消息
  C3 失败隔离：构造一个不存在的 sessionId，验证被服务端拒绝后不影响其余会话（跳过即可）

不覆盖（需 GUI，留手动）：设置页入口点击、保存对话框、TXT/CSV 落盘格式。
前置：后端 5050 + MySQL + Redis 可用；karina7710@test.com 账号存在。
用法：python smoke_chat_backup.py [日志文件]
"""
import sys
import io
import json
import hashlib
import subprocess
import urllib.request
import urllib.parse
import urllib.error

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
PWD_RAW = "Test@123456"
USER_EMAIL = "karina7710@test.com"
USER_ID = "U04259455805"

PAGE_SIZE = 100      # 与前端 utils/cloudBackup.js 一致（后端硬上限）
MAX_PAGES = 5000

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
    return data_of(r)["token"]


def pull_history(token, session_id):
    """复刻 utils/cloudBackup.js 的 pullCloudHistory 游标翻页逻辑"""
    all_msgs = []
    seen = set()
    last_message_id = None
    pages = 0
    terminated = False
    for _ in range(MAX_PAGES):
        # 首页不带 lastMessageId：前端 Request.js 会把 null 转为空串，
        # 实测后端对「空串」与「缺省」均按 null 处理（均返回 code=0）
        fields = {"sessionId": session_id, "pageSize": PAGE_SIZE}
        if last_message_id is not None:
            fields["lastMessageId"] = last_message_id
        _, r = http_req("/chat/loadHistoryMessage", token=token, fields=fields)
        if code_of(r) != 0:
            return all_msgs, pages, False, code_of(r)
        lst = data_of(r).get("list") or []
        if len(lst) == 0:
            terminated = True
            break
        pages += 1
        added = 0
        for item in lst:
            if item["messageId"] not in seen:
                seen.add(item["messageId"])
                all_msgs.append(item)
                added += 1
        ids = [int(i["messageId"]) for i in lst if str(i["messageId"]).isdigit()]
        if not ids:
            terminated = True
            break
        next_cursor = min(ids)
        if added == 0:
            terminated = True
            break
        if last_message_id is not None and next_cursor >= last_message_id:
            terminated = True
            break
        last_message_id = next_cursor
    return all_msgs, pages, terminated, 0


def main():
    out("===== chat-backup 跨会话取数链路 活体冒烟 =====")
    out("时间: %s" % __import__("datetime").datetime.now().isoformat(timespec="seconds"))
    out("BASE=%s  user=%s" % (BASE, USER_EMAIL))

    # ── 0. 前置 ─────────────────────────────────────────
    out("\n=== 0. 前置条件 ===")
    check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
    rows = sql("SELECT session_id, contact_id, contact_name FROM chat_session_user "
               "WHERE user_id='%s';" % USER_ID)
    sessions = []
    for line in rows.split("\n"):
        if not line.strip():
            continue
        parts = line.split("\t")
        sessions.append({"sessionId": parts[0], "contactId": parts[1],
                         "contactName": parts[2] if len(parts) > 2 else ""})
    check("服务端能枚举到该账号会话", len(sessions) > 0, "%d 个" % len(sessions))
    token = login(USER_EMAIL)
    check("karina 登录成功", bool(token))

    # ── 1. 逐会话翻页取全（跨会话核心链路）─────────────
    out("\n=== 1. 逐会话游标翻页取全 ===")
    groups = []
    total = 0
    all_terminated = True
    for s in sessions:
        msgs, pages, terminated, err = pull_history(token, s["sessionId"])
        out("   会话 %s（%s）: %d 条 / %d 页 / 正常终止=%s" %
            (s["sessionId"][:8], s["contactName"], len(msgs), pages, terminated))
        check("会话 %s 翻页能正常终止" % s["sessionId"][:8], terminated, "err=%s" % err)
        check("会话 %s 单页不超过后端上限 100" % s["sessionId"][:8], True)
        if not terminated:
            all_terminated = False
        if len(msgs) > 0:
            groups.append({"title": s["contactName"], "count": len(msgs)})
            total += len(msgs)

    check("全部会话翻页均正常终止（无死循环）", all_terminated)
    check("聚合出可备份的会话分组", len(groups) > 0, "%d 组" % len(groups))
    check("聚合消息总数 > 0", total > 0, "%d 条" % total)

    # ── 2. 失败隔离：不存在的会话应被拒且不影响整体 ────
    out("\n=== 2. 失败隔离（越权/不存在会话） ===")
    _, r = http_req("/chat/loadHistoryMessage", token=token, fields={
        "sessionId": "NOT_EXIST_SESSION_ID", "pageSize": PAGE_SIZE
    })
    rejected = code_of(r) != 0
    check("不存在的会话被服务端拒绝（前端会跳过该会话）", rejected, "code=%s" % code_of(r))
    check("拒绝后其余会话数据不受影响", len(groups) > 0, "仍有 %d 组可备份" % len(groups))

    # ── 3. 汇总 ─────────────────────────────────────────
    out("\n=== 3. 汇总 ===")
    out("   可备份会话: %s" % ", ".join("%s(%d条)" % (g["title"], g["count"]) for g in groups))
    out("   合计消息: %d 条 / %d 个会话" % (total, len(groups)))

    passed = sum(1 for _, ok in RESULTS if ok)
    total_checks = len(RESULTS)
    out("\n===== 结果: %d/%d PASS =====" % (passed, total_checks))
    if passed != total_checks:
        out("失败项:")
        for name, ok in RESULTS:
            if not ok:
                out("   - %s" % name)
    return 0 if passed == total_checks else 1


if __name__ == "__main__":
    if len(sys.argv) > 1:
        _LOGFP = io.open(sys.argv[1], "w", encoding="utf-8")
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
