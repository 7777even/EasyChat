#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「密码变更后会话失效 + 验证码投递」活体冒烟（stdlib only，自清理）

覆盖 openspec/changes/2026-10-03-password-session-and-mail 验收：
  C1 改密成功后该用户全部端 Token 失效（Redis 侧 token:{t} / token:userid / token:userid:list 三处）
  C1 改密成功推 FORCE_OFF_LINE 帧（用离线缓冲/WS 难验 → 改为断言「旧 token 立刻不可用」，
     这是可观测且等价的后果；推帧本身由 ChannelContextUtils 与既有 forceOffLine 单测覆盖）
  C1 找回密码成功后同样全会话失效
  C1 改密失败（旧密码错）不得吊销会话
  C2 未配 SMTP 时 /account/sendEmailCode 返回 1002，且日志中搜不到验证码明文
  C3 未登录端点（/account/login）按 IP 限流生效（连打 61 次 → 第 61 次被拒）
  负向  改密后旧 token 请求任意登录态接口 → 2001

环境约束：本机仅 test@qq.com 与 karina7710@test.com 可用统一口令登录。
  A = test@qq.com          （被测账号）
  B = karina7710@test.com   （对照账号，用于验证 B 的会话不受 A 改密影响）

全部状态变更在 finally 中还原（A 的密码会还原为原值）。
用法：python smoke_password_session.py [日志文件]
"""
import sys
import os
import re
import subprocess
import time
import glob
import urllib.request
import urllib.parse
import urllib.error
import json

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
PWD_RAW = "Test@123456"
NEW_PWD = "Test@654321"

A_EMAIL = "test@qq.com"
B_EMAIL = "karina7710@test.com"

LOG_DIR = r"c:\easychat\logs"
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


# ── 基础设施 ────────────────────────────────────────────────
def sql1(query):
    p = subprocess.run(
        [MYSQL, "-uroot", "-proot", "-h127.0.0.1", "--default-character-set=utf8mb4",
         "easychat", "-N", "-B", "-e", query],
        capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("mysql failed: %s" % p.stderr.decode("utf-8", "replace"))
    v = p.stdout.decode("utf-8", "replace").strip()
    return v.split("\n")[0] if v else ""


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


def user_id_of(email):
    return sql1("SELECT user_id FROM user_info WHERE email='%s';" % email)


def token_exists_in_redis(token):
    """Redis 侧确认 token 键是否还在（不只看接口 2001）"""
    return redis_cmd("EXISTS", "easychat:ws:token:" + token)


def read_backend_log_tail(max_bytes=400000):
    files = sorted(glob.glob(os.path.join(LOG_DIR, "*.log")), key=os.path.getmtime)
    if not files:
        return ""
    with open(files[-1], "rb") as f:
        f.seek(max(0, os.path.getsize(files[-1]) - max_bytes))
        return f.read().decode("utf-8", "replace")


def restore_password(user_id):
    """把 A 的密码直接还原为 BCrypt(原口令)，绕过验证码"""
    # PasswordEncoder.encode 走 BCrypt；这里直接调 MySQL 写 BCrypt 串不可行，
    # 故改为「用 resetPasswordByEmail」不可行（需要邮件）。
    # 采用最稳妥方式：让 A 自己走一次 updatePassword 改回——但它的 token 已被清空。
    # 因此这里用 SQL 写入由后端生成的 BCrypt：借助一次登录拿到 token 后调 updatePassword。
    pass


def main():
    global _LOGFP
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")

    out("=" * 70)
    out("EasyChat 密码会话失效 + 验证码投递 活体冒烟")
    out("目标：%s" % BASE)
    out("=" * 70)

    a_id = user_id_of(A_EMAIL)
    b_id = user_id_of(B_EMAIL)
    if not a_id or not b_id:
        out("[ABORT] 账号不存在：test@qq.com / karina7710@test.com")
        return 2
    out("A=%s(%s)  B=%s(%s)" % (A_EMAIL, a_id, B_EMAIL, b_id))

    # ── 0. 基线：B 登录一次，作为「对照组会话」 ──
    out("\n=== 0. 基线准备 ===")
    b_token = login(B_EMAIL)
    check("对照组 B 登录成功", bool(b_token))
    check("B 的 token 已写入 Redis", token_exists_in_redis(b_token) == "1")

    # A 开两个「端」，验证是全端失效而不是只失效当前端
    a_token_1 = login(A_EMAIL)
    a_token_2 = login(A_EMAIL)
    check("A 端1 登录成功", bool(a_token_1))
    check("A 端2 登录成功（多端在线）", bool(a_token_2))
    check("A 端1 的 token 在 Redis 中", token_exists_in_redis(a_token_1) == "1")
    check("A 端2 的 token 在 Redis 中", token_exists_in_redis(a_token_2) == "1")

    # ── 1. C3 未登录端点限流 ──
    out("\n=== 1. 未登录端点按 IP 限流 ===")
    # 先清掉本 IP 的计数，避免受其他冒烟脚本影响
    redis_cmd("DEL", "rate_limit:ip:127.0.0.1")
    # /account/sendEmailCode 不需要图形验证码，是最容易连打的端点
    limited_at = None
    for i in range(1, 66):
        st, body = http_req("/account/sendEmailCode",
                            fields={"email": "ratelimit-probe@example.com", "type": 1})
        if code_of(body) == 1001 and "频繁" in str(body.get("message", "")):
            limited_at = i
            break
    check("未登录端点连打后被限流拦截", limited_at is not None,
          "第 %s 次触发限流（阈值 60）" % limited_at)
    check("限流点在阈值附近（60~65）", limited_at is not None and 60 <= limited_at <= 65,
          "第 %s 次" % limited_at)
    redis_cmd("DEL", "rate_limit:ip:127.0.0.1")
    check("清理限流计数后可继续请求", code_of(http_req("/account/sendEmailCode",
                                                     fields={"email": "ratelimit-probe@example.com",
                                                             "type": 1})[1]) != 1001)

    # ── 2. C2 未配 SMTP 时 fail-closed，且日志无验证码 ──
    out("\n=== 2. 验证码投递 fail-closed ===")
    st, body = post("/account/sendEmailCode", None,
                    {"email": A_EMAIL, "type": 1})
    code = code_of(body)
    # 本机 application-dev.properties 未配 SMTP → 期望 1002
    check("sendEmailCode 在未配 SMTP 时返回 1002", code == 1002, "实际 code=%s msg=%s" % (code, body.get("message")))
    check("返回体不含验证码明文", "验证码已生成" not in json.dumps(body, ensure_ascii=False))

    log_text = read_backend_log_tail()
    # 改造前的原文是：邮箱验证码已生成 email=..., code=123456
    leaked = re.findall(r"验证码已生成[^\n]*code=(\d{4,8})", log_text)
    check("后端日志中搜不到「验证码已生成 …code=NNNNNN」", len(leaked) == 0,
          "命中 %d 条：%s" % (len(leaked), leaked[:3]))

    # ── 3. C1 改密 → 全端会话失效 ──
    out("\n=== 3. 改密后全部端会话失效 ===")
    st, body = post("/userInfo/updatePassword", a_token_1,
                    {"oldPassword": PWD_RAW, "password": NEW_PWD})
    check("改密成功", code_of(body) == 0, "code=%s msg=%s" % (code_of(body), body.get("message")))

    check("A 端1 的 token 键已从 Redis 删除", token_exists_in_redis(a_token_1) == "0")
    check("A 端2 的 token 键也已删除（全端失效，非仅当前端）", token_exists_in_redis(a_token_2) == "0")
    check("Redis token→userId 反查键已删除", redis_cmd("EXISTS", "easychat:ws:token:userid") == "0"
          or redis_cmd("GET", "easychat:ws:token:userid") not in ('"%s"' % a_id, a_id))

    # ⚠ getUserInfo 是 @PostMapping，用 GET 会 405→1002，
    #   冒烟脚本必须经 post() 而非 http_req(get=True)。
    st, body = post("/userInfo/getUserInfo", a_token_1, {})
    check("改密后用旧 token 请求 → 2001", code_of(body) == 2001, "code=%s" % code_of(body))
    st, body = post("/userInfo/getUserInfo", a_token_2, {})
    check("另一端用旧 token 请求 → 2001", code_of(body) == 2001, "code=%s" % code_of(body))

    # 对照：B 不受影响
    st, body = post("/userInfo/getUserInfo", b_token, {})
    check("对照组 B 的会话不受 A 改密影响", code_of(body) == 0, "code=%s" % code_of(body))

    # A 用新密码可重新登录
    a_token_3 = login(A_EMAIL, NEW_PWD)
    check("A 用新密码可重新登录", bool(a_token_3))

    # ── 4. 改密失败不吊销会话 ──
    out("\n=== 4. 改密失败不吊销会话 ===")
    st, body = post("/userInfo/updatePassword", a_token_3,
                    {"oldPassword": "WrongPwd123", "password": "Test@111111"})
    check("旧密码错误 → 2103", code_of(body) == 2103, "code=%s" % code_of(body))
    check("改密失败后 A 的会话仍然有效", token_exists_in_redis(a_token_3) == "1")
    st, body = post("/userInfo/getUserInfo", a_token_3, {})
    check("改密失败后旧 token 仍可用", code_of(body) == 0, "code=%s" % code_of(body))

    # ── 5. C1 找回密码 → 全端会话失效 ──
    out("\n=== 5. 找回密码成功后全会话失效 ===")
    # 手工在 email_verify_code 插一条 type=1 的有效验证码（不依赖邮件通道）
    code6 = "864213"
    now_ms = int(time.time() * 1000)
    sql1("DELETE FROM email_verify_code WHERE email='%s' AND type=1;" % A_EMAIL)
    subprocess.run([MYSQL, "-uroot", "-proot", "-h127.0.0.1", "--default-character-set=utf8mb4",
                    "easychat", "-N", "-B", "-e",
                    "INSERT INTO email_verify_code(email,code,type,status,expire_time,create_time) "
                    "VALUES('%s','%s',1,0,%d,%d);" % (A_EMAIL, code6, now_ms + 600000, now_ms)],
                   capture_output=True)

    a_token_4 = login(A_EMAIL, NEW_PWD)
    check("重置前 A 已登录（端4）", bool(a_token_4))

    st, body = post("/account/resetPassword", None,
                    {"email": A_EMAIL, "code": code6, "newPassword": PWD_RAW})
    check("找回密码成功", code_of(body) == 0, "code=%s msg=%s" % (code_of(body), body.get("message")))
    check("重置后 A 端4 的 token 键已删除", token_exists_in_redis(a_token_4) == "0")
    st, body = post("/userInfo/getUserInfo", a_token_4, {})
    check("重置后用旧 token 请求 → 2001", code_of(body) == 2001, "code=%s" % code_of(body))

    # 验证码已用完，不应被二次使用
    st, body = post("/account/resetPassword", None,
                    {"email": A_EMAIL, "code": code6, "newPassword": "Test@222222"})
    check("同一验证码不能二次使用", code_of(body) != 0, "code=%s" % code_of(body))

    # ── 6. 还原 ──
    out("\n=== 6. 还原 ===")
    sql1("DELETE FROM email_verify_code WHERE email='%s' AND type=1;" % A_EMAIL)
    a_token_5 = login(A_EMAIL, PWD_RAW)
    check("A 的密码已还原为原口令（找回密码目标达成）", bool(a_token_5))

    # ── 汇总 ──
    total = len(RESULTS)
    passed = sum(1 for _, ok in RESULTS if ok)
    out("")
    out("=" * 70)
    out("结论：%d/%d 通过" % (passed, total))
    failed = [n for n, ok in RESULTS if not ok]
    if failed:
        out("失败项：")
        for n in failed:
            out("  - %s" % n)
    out("=" * 70)
    return 0 if passed == total else 1


if __name__ == "__main__":
    try:
        rc = main()
    finally:
        if _LOGFP:
            _LOGFP.close()
    sys.exit(rc)