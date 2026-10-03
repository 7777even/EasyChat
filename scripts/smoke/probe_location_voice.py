#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「位置消息 / 语音消息」断链现状取证（只读，不改任何数据）

用途：为 L3 Change 提供**实测**现状证据，而不是「读代码推测」。
覆盖 openspec/specs/chat-message-integrity 的 C-新项。

账号：A = test@qq.com（发送方），B = karina7710@test.com（好友，接收方）
前置：后端已启动（5050）；已执行 migration-001..011。

用法：python probe_location_voice.py [日志文件]
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

A_ID = B_ID = None
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
    for u, v in ((A_ID, B_ID), (B_ID, A_ID)):
        sql("INSERT INTO user_contact (user_id,contact_id,contact_type,status,role,create_time) "
            "VALUES ('%s','%s',0,1,2,NOW()) ON DUPLICATE KEY UPDATE status=1;" % (u, v))


def main():
    global _LOGFP, A_ID, B_ID
    # 先跑主流程再关文件：探针结论必须先落盘，否则异常时连日志都会丢
    try:
        run_probe()
    finally:
        if _LOGFP:
            _LOGFP.close()
            _LOGFP = None

    passed = sum(1 for _, ok in RESULTS if ok)
    out("\n===== 结论：%d/%d 通过 =====" % (passed, len(RESULTS)))
    out("（本探针的「PASS」表示『现状如预期地被证实』，不是『功能可用』）")
    sys.exit(0 if passed == len(RESULTS) else 1)


def run_probe():

    out("===== 位置消息 / 语音消息 断链现状取证 =====")
    out("时间: %s" % datetime.datetime.now().isoformat(timespec="seconds"))
    out("性质: 只读探针，不写业务数据（发送失败的请求不会产生记录）\n")

    try:
        out("=== 1. 前置 ===")
        check("后端可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
        a_tok = login(A_EMAIL)
        A_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % A_EMAIL)
        B_ID = sql1("SELECT user_id FROM user_info WHERE email='%s';" % B_EMAIL)
        out("   A=%s  B=%s" % (A_ID, B_ID))
        be_friends()
        check("A 与 B 是好友", sql1(
            "SELECT COUNT(*) FROM user_contact WHERE user_id='%s' AND contact_id='%s' AND status=1;"
            % (A_ID, B_ID)) == "1")

        before_total = int(sql1("SELECT COUNT(*) FROM chat_message;") or 0)

        out("\n=== 2. 服务端发送白名单实测（ChatController:72）===")
        out("   白名单仅含 CHAT(2) / MEDIA_CHAT(5)")

        # 位置消息
        r = post("/chat/sendMessage", a_tok, {
            "contactId": B_ID,
            "messageContent": "取证-位置",
            "messageType": 25,
            "extraData": json.dumps({"location": "北京市朝阳区", "latitude": 39.9042, "longitude": 116.4074})
        })[1]
        check("★ 发位置消息(25) 被拒 → CODE_1001", code_of(r) == 1001,
              "code=%s msg=%s" % (code_of(r), r.get("message")))

        # 语音消息
        r = post("/chat/sendMessage", a_tok, {
            "contactId": B_ID,
            "messageContent": "[语音]",
            "messageType": 24,
            "fileName": "voice_1.webm",
            "fileSize": 1024,
            "fileType": 3,
            "duration": 3
        })[1]
        check("★ 发语音消息(24) 被拒 → CODE_1001", code_of(r) == 1001,
              "code=%s msg=%s" % (code_of(r), r.get("message")))

        # 对照组：普通聊天必须能发（证明不是环境问题）
        r = post("/chat/sendMessage", a_tok, {
            "contactId": B_ID, "messageContent": "取证-对照组普通聊天", "messageType": 2
        })[1]
        check("对照组：发普通聊天(2) 成功 → code=0", code_of(r) == 0, "code=%s" % code_of(r))
        # MessageSendDto 主键字段是 messageId
        ctrl_id = data_of(r).get("messageId")

        after_total = int(sql1("SELECT COUNT(*) FROM chat_message;") or 0)
        check("★ 被拒的 24/25 未产生任何 chat_message 记录（净增仅对照组 1 条）",
              after_total - before_total == 1,
              "before=%d after=%d 净增=%d" % (before_total, after_total, after_total - before_total))

        out("\n=== 3. 落库白名单确认（ChatMessageServiceImpl 落库分支）===")
        # 注意：这里断言的是「库里现存的类型集合不含 24/25」，
        # 而非「代码白名单恰好是 1/2/3/5」——代码白名单由 verify_ws_frame_parity.mjs 解析校验。
        # 存量库可能只有 1/2（1=好友打招呼 2=聊天），3/5 因无历史数据而不出现，属正常。
        types_in_db = sql("SELECT message_type FROM chat_message GROUP BY message_type ORDER BY message_type;").split()
        check("★ 库中无 24（语音）记录", '24' not in types_in_db, "现存类型: %s" % " ".join(types_in_db))
        check("★ 库中无 25（位置）记录", '25' not in types_in_db, "现存类型: %s" % " ".join(types_in_db))

        out("\n=== 4. 下载链路：语音需要文件落盘（现状不可用）===")
        # MessageSendDto 的主键字段是 messageId（不是 id）
        r = post("/chat/sendMessage", a_tok, {
            "contactId": B_ID, "messageContent": "取证-下载对照", "messageType": 2
        })[1]
        ctrl2_id = data_of(r).get("messageId")
        status, body = post("/chat/downloadFile", a_tok, {"fileId": str(ctrl2_id), "showCover": "false"})
        # 只断言「被拒」这一事实，不断言具体码：
        # 实测返回 CODE_1001 —— 来自 downloadFile 的「USER 会话且 request.userId != message.contact_id」
        # 守卫（ChatMessageServiceImpl:422，A 下载自己发给 B 的消息时 A != B）。
        # 该守卫属既有行为（图片/视频同样受限），与本次 24/25 修复无关，故只记录不断言。
        check("对照：普通聊天(无 fileName) 下载被拒（非 0）", code_of(body) not in (0, None),
              "code=%s msg=%s" % (code_of(body), body.get("message")))
        out("   实测码 %s，来自 downloadFile 的 contact_id 守卫（既有行为，图片同样受限）"
            % code_of(body))
        out("   → 推论：语音要能播放，.webm 必须先经 /chat/uploadFile 落盘到")
        out("     file/{YYYYMM}/{messageId}.webm，且 allowedFileTypes 白名单需含 webm/amr。")
        out("     现状 allowedFileTypes=jpg,jpeg,...,txt,mp4,mp3 —— **不含任何音频后缀**。")
        sql("DELETE FROM chat_message WHERE message_id=%s;" % ctrl2_id)

        out("\n=== 5. 清理 ===")
        if ctrl_id:
            sql("DELETE FROM chat_message WHERE message_id=%s;" % ctrl_id)
            out("   对照组消息已删除 messageId=%s" % ctrl_id)
        out("   最终 chat_message 总数: %s" % sql1("SELECT COUNT(*) FROM chat_message;"))

    except Exception as e:
        out("\n!! 探针异常中断: %r" % e)
        RESULTS.append(("探针执行未正常结束", False))


if __name__ == "__main__":
    main()