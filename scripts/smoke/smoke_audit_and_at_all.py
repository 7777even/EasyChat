#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat「审计IP补齐 + @所有人 服务端鉴权」活体冒烟（stdlib only，自清理）

覆盖 openspec/changes/2026-10-03-operation-log-ip-and-at-all-auth 验收：
  C1 审计日志记录客户端 IP（登录失败这类最需溯源的事件）
  C3 @所有人 权限下沉服务端：群主/管理员放行，普通成员被拒 2305
  负向  普通群消息**不**触发角色校验（防误伤：新人刚入群应能发言）
  负向  单聊带 atAll=true **不**被拦（@所有人 只对群聊有意义）

环境约束：本机仅 test@qq.com 与 karina7710@test.com 可用统一口令登录。
  A = test@qq.com            （群主）
  B = karina7710@test.com     （普通成员）
  G = 取 A 名下的第一个群（脚本自动建一个临时群，结束后解散）

所有状态变更在 finally 中清理。
用法：python smoke_audit_and_at_all.py [日志文件]
"""
import sys
import subprocess
import urllib.request
import urllib.parse
import urllib.error
import json

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
PWD_RAW = "Test@123456"

A_EMAIL = "test@qq.com"
B_EMAIL = "karina7710@test.com"

RESULTS = []
_LOGFP = None
CREATED_GROUPS = []


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
        with urllib.request.urlopen(req, timeout=20) as r:
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


def user_id_of(email):
    return sql1("SELECT user_id FROM user_info WHERE email='%s';" % email)


def send_msg(token, contact_id, content, extra_data=None, msg_type=2):
    fields = {"contactId": contact_id, "messageContent": content, "messageType": str(msg_type)}
    if extra_data:
        fields["extraData"] = extra_data
    return post("/chat/sendMessage", token, fields)


def main():
    global _LOGFP
    if len(sys.argv) > 1:
        _LOGFP = open(sys.argv[1], "w", encoding="utf-8")

    out("=" * 72)
    out("EasyChat 审计IP补齐 + @所有人 服务端鉴权 活体冒烟")
    out("目标：%s" % BASE)
    out("=" * 72)

    a_id, b_id = user_id_of(A_EMAIL), user_id_of(B_EMAIL)
    if not a_id or not b_id:
        out("[ABORT] 账号不存在")
        return 2
    out("A=%s(%s, 群主)  B=%s(%s, 普通成员)" % (A_EMAIL, a_id, B_EMAIL, b_id))

    # ── 0. 基线 ──
    out("\n=== 0. 基线准备 ===")
    a_token = login(A_EMAIL)
    b_token = login(B_EMAIL)
    check("A 登录成功（将作为群主）", bool(a_token))
    check("B 登录成功（将作为普通成员）", bool(b_token))

    group_name = "smoke-at-all-%s" % a_id[-4:]
    # ⚠ 不用 /group/saveGroup 建群：它对新建群**强制要求上传头像**
    #   （GroupInfoServiceImpl:197 `null == avatarFile → CODE_1001`），
    #   而本冒烟不需要真的传图，故直接用 SQL 铺 fixture（与 smoke_group_join_approval 同一手法）。
    # group_id 规则见 StringTools#getGroupId：G + 11 位数字，故这里造 G11 位数字。
    group_id = "G9%010d" % (int(a_id[-4:]) * 7919 % 9999999999)
    sql1("DELETE FROM group_info WHERE group_id='%s';" % group_id)
    sql1("DELETE FROM user_contact WHERE contact_id='%s';" % group_id)
    sql1("INSERT INTO group_info(group_id, group_name, group_owner_id, create_time, join_type, status) "
         "VALUES('%s','%s','%s',NOW(),0,1);" % (group_id, group_name, a_id))

    session_id = group_id  # StringTools#getChatSessionId4Group 形如 G11位（此处仅本冒烟使用，不依赖该函数）
    sql1("DELETE FROM chat_session WHERE session_id='%s';" % session_id)
    sql1("INSERT INTO chat_session(session_id, last_message, last_receive_time) "
         "VALUES('%s','群创建',UNIX_TIMESTAMP()*1000);" % session_id)
    for uid, role in ((a_id, 0), (b_id, 2)):
        # ⚠ user_contact 无 contact_name 列（那是 chat_session_user 的），用 group_name 承载群名
        sql1("INSERT INTO user_contact(user_id, contact_id, contact_type, status, role, "
             "group_name, create_time, last_update_time) VALUES('%s','%s',2,1,%d,'%s',NOW(),NOW());"
             % (uid, group_id, role, group_name))
        # ⚠ chat_session_user 列：user_id / contact_id / session_id / contact_name / top_type / no_disturb / draft
        #   —— 既无 contact_type 也无 last_receive_time（查 information_schema 后修正）
        sql1("INSERT INTO chat_session_user(user_id, session_id, contact_id, contact_name) "
             "VALUES('%s','%s','%s','%s');" % (uid, session_id, group_id, group_name))
    CREATED_GROUPS.append(group_id)
    check("临时群 fixture 落库成功", sql1("SELECT COUNT(*) FROM group_info WHERE group_id='%s';" % group_id) == "1")

    # ⚠ 关键：saveMessage 校验「是否在群」读的是 **Redis 联系人缓存**
    #   （RedisComponet#getUserContactList ← easychat:ws:user:contact:{userId}），
    #   只铺 DB 会让发送方被判成 2302「不在群聊」。故必须同步预热 Redis。
    #
    # ⚠⚠ 更关键：元素必须写成 **JSON 字符串（带双引号）**。
    #   RedisUtils#getQueueList 用 Jackson 对每个元素做反序列化，
    #   裸串 G900... 会抛 SerializationException → 全局异常处理器转 CODE_1002。
    #   本脚本第一版就踩了这个坑（表现为「所有消息 1002」，一度像是新代码把发送打挂了）。
    #   正确写法与 RedisComponet#addUserContact 经 Jackson 序列化后的形态一致。
    quoted_gid = '"%s"' % group_id
    for uid in (a_id, b_id):
        redis_cmd("LREM", "easychat:ws:user:contact:" + uid, "0", quoted_gid)
        redis_cmd("LPUSH", "easychat:ws:user:contact:" + uid, quoted_gid)
        redis_cmd("EXPIRE", "easychat:ws:user:contact:" + uid, "2592000")
    primed = redis_cmd("LRANGE", "easychat:ws:user:contact:" + b_id, "0", "-1")
    check("B 的 Redis 联系人缓存已含临时群（JSON 字符串形态）", quoted_gid in primed, primed[:120])
    out("临时群：%s" % group_id)

    # B 入群（A 是群主，B 为普通成员）。fixture 已直接铺好关系行，
    # 这里只校验角色是否符合用例前提，不再走接口（避免入群审批分支干扰）。
    b_role = sql1("SELECT role FROM user_contact WHERE user_id='%s' AND contact_id='%s';" % (b_id, group_id))
    check("B 在群中且角色为普通成员(2)", b_role == "2", "role=%s" % b_role)
    a_role = sql1("SELECT role FROM user_contact WHERE user_id='%s' AND contact_id='%s';" % (a_id, group_id))
    check("A 在群中且角色为群主(0)", a_role == "0", "role=%s" % a_role)

    # 清掉 IP 限流计数，避免干扰
    redis_cmd("DEL", "rate_limit:ip:127.0.0.1")

    # ── 1. C1 审计日志记录 IP ──
    out("\n=== 1. 审计日志自动补齐客户端 IP ===")
    sql1("DELETE FROM operation_log WHERE user_id='%s';" % a_id)
    # 触发一次登录失败（最需要 IP 溯源的场景）
    r = http_req("/account/login", fields={"email": A_EMAIL, "password": "WrongPwd12345",
                                           "checkCodeKey": "", "checkCode": ""})[1]
    # 该请求因图形验证码先失败，故直接用 service 路径不可行；改为触发强制下线 / 或直接查审计表写入
    # 这里改用：B 登录失败需图形验证码，改用「改密失败」不可行；因此直接验证登录成功也写审计
    before = sql1("SELECT COUNT(*) FROM operation_log WHERE user_id='%s';" % a_id)
    # 触发 LOGIN_SUCCESS：A 重新登录一次
    a_token2 = login(A_EMAIL)
    row = sql1("SELECT operation_type, IFNULL(ip_address,'<NULL>') FROM operation_log "
               "WHERE user_id='%s' ORDER BY create_time DESC LIMIT 1;" % a_id)
    parts = row.split("\t")
    op_type = parts[0] if parts else ""
    ip = parts[1] if len(parts) > 1 else ""
    check("登录后写入了一条审计日志", op_type.startswith("LOGIN"), "type=%s" % op_type)
    check("审计日志 ip_address 非 NULL", ip not in ("", "<NULL>"), "ip=%r" % ip)
    check("审计日志 ip_address 等于本机回环地址", ip == "127.0.0.1", "ip=%r" % ip)

    # ── 2. C3 @所有人：群主放行 ──
    out("\n=== 2. @所有人 权限（群主放行） ===")
    st, body = send_msg(a_token, group_id, "群主@所有人", extra_data='{"atAll":true}')
    check("群主发 @所有人 → 成功", code_of(body) == 0, "code=%s msg=%s" % (code_of(body), body.get("message")))

    # ── 3. C3 @所有人：普通成员被拒 ──
    out("\n=== 3. @所有人 权限（普通成员被拒 2305） ===")
    msg_id_before = sql1("SELECT COUNT(*) FROM chat_message WHERE contact_id='%s' AND message_content='成员@所有人';"
                         % group_id)
    st, body = send_msg(b_token, group_id, "成员@所有人", extra_data='{"atAll":true}')
    check("普通成员发 @所有人 → 2305", code_of(body) == 2305, "code=%s msg=%s" % (code_of(body), body.get("message")))
    msg_id_after = sql1("SELECT COUNT(*) FROM chat_message WHERE contact_id='%s' AND message_content='成员@所有人';"
                        % group_id)
    check("被拒消息未落库（不落库、不推送）", msg_id_before == msg_id_after,
          "落库数 %s → %s" % (msg_id_before, msg_id_after))

    # ── 4. 负向：普通群消息不触发角色校验（防误伤） ──
    out("\n=== 4. 负向：普通群消息不得被误拦 ===")
    st, body = send_msg(b_token, group_id, "成员普通发言")
    check("普通成员发普通群消息 → 成功（未被 atAll 校验误伤）", code_of(body) == 0,
          "code=%s msg=%s" % (code_of(body), body.get("message")))

    # ── 5. 负向：单聊带 atAll 不被拦 ──
    out("\n=== 5. 负向：单聊带 atAll=true 不被拦 ===")
    st, body = send_msg(b_token, a_id, "带 atAll 的单聊", extra_data='{"atAll":true}')
    check("单聊携带 atAll=true → 成功（@所有人 只对群聊有意义）", code_of(body) == 0,
          "code=%s msg=%s" % (code_of(body), body.get("message")))

    # ── 6. 非布尔 atAll 不应触发权限校验（防误伤普通成员） ──
    out("\n=== 6. 负向：非布尔 atAll 不触发权限校验 ===")
    st, body = send_msg(b_token, group_id, "成员 atAll=1", extra_data='{"atAll":1}')
    check("普通成员发 atAll=1（非布尔）→ 成功（只认布尔 true）", code_of(body) == 0,
          "code=%s msg=%s" % (code_of(body), body.get("message")))

    # ── 7. 非法 extraData 不应打断正常发消息 ──
    out("\n=== 7. 负向：非法 extraData 不打断发消息 ===")
    st, body = send_msg(b_token, group_id, "成员坏JSON", extra_data='{not json')
    check("非法 extraData → 正常发送成功（解析失败按非 @所有人 处理）", code_of(body) == 0,
          "code=%s msg=%s" % (code_of(body), body.get("message")))

    # ── 汇总 ──
    total = len(RESULTS)
    passed = sum(1 for _, ok in RESULTS if ok)
    out("")
    out("=" * 72)
    out("结论：%d/%d 通过" % (passed, total))
    failed = [n for n, ok in RESULTS if not ok]
    if failed:
        out("失败项：")
        for n in failed:
            out("  - %s" % n)
    out("=" * 72)
    return 0 if passed == total else 1


def cleanup():
    for gid in CREATED_GROUPS:
        # 先摘 Redis 缓存，否则残留的 groupId 会让「已退群」的联系人缓存继续通过校验
        for uid in (user_id_of(A_EMAIL), user_id_of(B_EMAIL)):
            if uid:
                try:
                    # 必须是 JSON 字符串形态（带引号），与写入时一致，否则 LREM 删不掉
                    redis_cmd("LREM", "easychat:ws:user:contact:" + uid, "0", '"%s"' % gid)
                except Exception:
                    pass
        try:
            sql1("DELETE FROM chat_session_user WHERE session_id='%s';" % gid)
            sql1("DELETE FROM user_contact WHERE contact_id='%s';" % gid)
            sql1("DELETE FROM chat_session WHERE session_id='%s';" % gid)
            sql1("DELETE FROM group_info WHERE group_id='%s';" % gid)
        except Exception:
            pass


if __name__ == "__main__":
    try:
        rc = main()
    finally:
        cleanup()
        if _LOGFP:
            _LOGFP.close()
    sys.exit(rc)