#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat 管理端「通话记录」活体冒烟（stdlib only）

覆盖 engineering/qa/2026-09-29-call-log-admin.md 的验收：
  C1 管理员分页筛选通话记录（类型/媒体/状态/发起人/时间范围）+ 默认 15 条分页 + id desc 排序
  C1 JOIN 解析发起方/对方昵称与群名（NULL 昵称前端占位）
  C1 orderBy 注入尝试被服务端硬编码覆盖（表不被污染）
  C2 非管理员 → HTTP 400 + body 1003（checkAdmin 拦截）；无 token → HTTP 401 + body 2001
     （注：旧码 901 已于 bdf854f 迁移为 2001；HTTP 404+1003 仅属死路由分支，
       BusinessException(1003) 经 inferHttpStatus 兜底映射为 HTTP 400）

前置：后端 5050 已启动、MySQL/Redis 可用、migration-008 call_log 表已建。
用法：python smoke_call_log.py [日志文件]

约定：fixture 以 SMOKE 标记（caller_id / user_id / group_id），跑完全量清理；
      DB 断言只读数值/ASCII 字段，中文经 API JSON(utf-8) 断言。
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

FIX_CALLER = "SMKCALL0001"            # ≤12（user_info.user_id 上限）
FIX_PEER = "SMKPEER0001"              # ≤12
FIX_GROUP = "SMOKEGRP01"              # ≤12（group_info.group_id 上限）
FIX_GHOST = "SMKGHOST001"             # 注销用户（昵称联表 NULL/占位验证用）
FIX_NICK_CALLER = "SMOKE-NICK-呼主"
FIX_NICK_PEER = "SMOKE-NICK-对端"
FIX_GROUP_NAME = "SMOKE群-通记录"

# 18 行 fixture：单聊 10 + 群呼 8（其中 1 行 end_time=NULL 测 durationMs，1 行 40 天前测时间窗外）
N_SINGLE = 10
N_GROUP = 8
TOTAL = N_SINGLE + N_GROUP

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


def load_logs(token, params):
    _, r = post("/admin/callLog/loadCallLog", token, params)
    if code_of(r) != 0:
        return None, None
    d = data_of(r)
    return d.get("totalCount"), d.get("list") or []


# ── fixture 管理 ────────────────────────────────────────────
def precleanup():
    sql("DELETE FROM call_log WHERE caller_id IN ('%s') OR caller_id='%s';" % (FIX_CALLER, FIX_GHOST))
    sql("DELETE FROM user_info WHERE user_id IN ('%s','%s','%s');" % (FIX_CALLER, FIX_PEER, FIX_GHOST))
    sql("DELETE FROM group_info WHERE group_id='%s';" % FIX_GROUP)


def seed():
    """返回 (最近17行的最小create_time, 40天前create_time, now)"""
    now = int(time.time() * 1000)
    sql("INSERT INTO user_info (user_id,email,nick_name,password,status,join_type,sex,create_time) VALUES "
        "('%s','smoke-call-caller@test.com','%s','%s',1,1,1,NOW()), "
        "('%s','smoke-call-peer@test.com','%s','%s',1,1,0,NOW());"
        % (FIX_CALLER, FIX_NICK_CALLER, hashlib.md5(PWD_RAW.encode()).hexdigest(),
           FIX_PEER, FIX_NICK_PEER, hashlib.md5(PWD_RAW.encode()).hexdigest()))
    sql("INSERT INTO group_info (group_id,group_name,group_owner_id,create_time,join_type,status) VALUES "
        "('%s','%s','%s',NOW(),0,1);" % (FIX_GROUP, FIX_GROUP_NAME, FIX_CALLER))

    rows = []
    min_recent = now
    # 单聊 10 行：媒体 1音频×6 + 2音视频×4；状态 1已接×5 + 2未接×3 + 3拒接×2；create_time 最近 10 分钟
    for i in range(N_SINGLE):
        ct = now - (i + 1) * 60000
        media = 1 if i < 6 else 2
        status = 1 if i < 5 else (2 if i < 8 else 3)
        rows.append("('%s',1,'%s',NULL,%d,%d,%d,%d,2,%d)"
                    % (FIX_CALLER, FIX_PEER, media, ct - 300000, ct - 240000, status, ct))
    # 群呼 8 行：全部 media=2 status=1 participant=5；第 7 行 end_time=NULL；第 8 行 40 天前（窗外）
    old_ct = now - 40 * 86400000
    for j in range(N_GROUP):
        if j == N_GROUP - 1:
            ct = old_ct
        else:
            ct = now - (11 + j) * 60000            # 最近 11~17 分钟（1h 窗内）
            min_recent = min(min_recent, ct)
        end_time = "NULL" if j == N_GROUP - 2 else str(ct - 240000)
        rows.append("('%s',2,NULL,'%s',2,%d,%s,1,5,%d)"
                    % (FIX_CALLER, FIX_GROUP, ct - 300000, end_time, ct))
    sql("INSERT INTO call_log "
        "(caller_id,call_type,peer_id,group_id,media_type,start_time,end_time,status,participant_count,create_time) VALUES "
        + ",".join(rows) + ";")
    return min_recent, old_ct, now


def real_baseline():
    return int(sql1("SELECT COUNT(*) FROM call_log WHERE caller_id NOT IN ('%s');" % FIX_CALLER) or 0)


def postcleanup():
    precleanup()


def main():
    out("===== call-log-admin 活体冒烟 =====")
    out("时间: %s" % __import__("datetime").datetime.now().isoformat(timespec="seconds"))
    out("BASE=%s  admin=%s  user=%s" % (BASE, ADMIN_EMAIL, USER_EMAIL))

    # ── 0. 前置条件 ────────────────────────────────────────
    out("\n=== 0. 前置条件 ===")
    check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
    check("migration-008 call_log 表存在",
          sql1("SELECT COUNT(*) FROM information_schema.tables "
               "WHERE table_schema='easychat' AND table_name='call_log';") == "1")
    precleanup()
    pre_real = real_baseline()
    check("前置：SMOKE fixture 已清空",
          sql1("SELECT COUNT(*) FROM call_log WHERE caller_id='%s';" % FIX_CALLER) == "0")
    out("   基线：真实通话记录 %d 行（不污染）" % pre_real)

    min_recent, old_ct, now = seed()
    seeded = sql1("SELECT COUNT(*) FROM call_log WHERE caller_id='%s';" % FIX_CALLER)
    check("fixture 落库 %d 行（单聊%d+群呼%d）" % (TOTAL, N_SINGLE, N_GROUP),
          seeded == str(TOTAL), "seeded=%s" % seeded)

    # ── 1. 登录 ────────────────────────────────────────────
    out("\n=== 1. 登录 ===")
    admin_token, admin_vo = login(ADMIN_EMAIL)
    check("admin 登录成功", bool(admin_token), "userId=%s" % admin_vo.get("userId"))
    check("admin 身份 admin=true", admin_vo.get("admin") is True, admin_vo.get("admin"))
    user_token, user_vo = login(USER_EMAIL)
    check("普通用户登录成功", bool(user_token))
    check("普通用户 admin=false", user_vo.get("admin") is False, user_vo.get("admin"))

    # ── 2. 管理端列表 + 分页 + 排序 ─────────────────────────
    out("\n=== 2. 管理端列表 / 分页 / 排序 ===")
    total, lst = load_logs(admin_token, {"pageNo": 1, "pageSize": 50})
    check("列表返回全部 %d 条" % TOTAL, total == TOTAL, "totalCount=%s" % total)
    check("行含核心字段（callType/mediaType/status/createTime）",
          all(x.get("callType") in (1, 2) and x.get("mediaType") in (1, 2)
              and x.get("status") in (1, 2, 3) and x.get("createTime") for x in lst) if lst else False)
    ids = [x.get("id") for x in lst]
    check("排序 id desc（首行最大、整体降序）",
          bool(ids) and ids == sorted(ids, reverse=True), "head=%s" % (ids[:3],))

    total2, lst2 = load_logs(admin_token, {"pageNo": 1})
    check("缺省 pageSize 默认 15 条（SIZE15）",
          total2 == TOTAL and len(lst2) == 15, "total=%s len=%s" % (total2, len(lst2)))
    total3, lst3 = load_logs(admin_token, {"pageNo": 2, "pageSize": 10})
    check("分页 pageNo=2/size=10 → 8 条",
          total3 == TOTAL and len(lst3) == 8, "total=%s len=%s" % (total3, len(lst3)))

    # 单聊/群呼行字段与计算列
    single = [x for x in lst if x.get("callType") == 1]
    group = [x for x in lst if x.get("callType") == 2]
    check("单聊/群发行数 10/8", len(single) == N_SINGLE and len(group) == N_GROUP,
          "single=%d group=%d" % (len(single), len(group)))
    s0 = single[0] if single else {}
    check("单聊行 durationMs = end-start 精确计算",
          s0.get("durationMs") == (s0.get("endTime") - s0.get("startTime"))
          if s0.get("endTime") and s0.get("startTime") else False,
          "start=%s end=%s dur=%s" % (s0.get("startTime"), s0.get("endTime"), s0.get("durationMs")))
    check("单聊行参与人数=2、群呼=5",
          all(x.get("participantCount") == 2 for x in single)
          and all(x.get("participantCount") == 5 for x in group))
    null_dur = [x for x in group if x.get("endTime") is None]
    check("end_time=NULL 行 durationMs 为 null（不报错）",
          len(null_dur) == 1 and null_dur[0].get("durationMs") is None,
          "null_rows=%d" % len(null_dur))

    # ── 3. JOIN 昵称 / 群名 ────────────────────────────────
    out("\n=== 3. JOIN 昵称与群名 ===")
    check("单聊行 callerNickName 已联表",
          all(x.get("callerNickName") == FIX_NICK_CALLER for x in single))
    check("单聊行 peerNickName 已联表",
          all(x.get("peerNickName") == FIX_NICK_PEER for x in single))
    check("群发行 groupNickName 已联表",
          all(x.get("groupNickName") == FIX_GROUP_NAME for x in group))
    check("单聊行无群名 / 群发行无对端昵称（字段隔离）",
          all(x.get("groupNickName") is None for x in single)
          and all(x.get("peerNickName") is None for x in group))
    # 注销用户昵称联表（status=0 不影响 JOIN 返回）
    ghost = FIX_GHOST
    sql("INSERT INTO user_info (user_id,email,nick_name,password,status,join_type,sex,create_time) VALUES "
        "('%s','smoke-ghost@test.com','SMOKE-NICK-幽灵','%s',0,1,1,NOW());" % (ghost, hashlib.md5(PWD_RAW.encode()).hexdigest()))
    ct = now - 5 * 60000
    sql("INSERT INTO call_log (caller_id,call_type,peer_id,media_type,start_time,end_time,status,participant_count,create_time) "
        "VALUES ('%s',1,'%s',1,%d,%d,2,2,%d);" % (ghost, FIX_PEER, ct - 60000, ct, ct))
    _, r = post("/admin/callLog/loadCallLog", admin_token, {"callerId": ghost})
    glist = data_of(r).get("list") or []
    check("注销用户行 nickName 仍返回（联表成功，非占位逻辑）",
          code_of(r) == 0 and len(glist) == 1 and glist[0].get("callerNickName") == "SMOKE-NICK-幽灵",
          "nicks=%s" % [x.get("callerNickName") for x in glist])
    sql("DELETE FROM call_log WHERE caller_id='%s';" % ghost)
    sql("DELETE FROM user_info WHERE user_id='%s';" % ghost)

    # ── 4. 四类筛选 + 时间范围含边界 ────────────────────────
    out("\n=== 4. 筛选与时间范围 ===")
    def cnt(params):
        _, r = post("/admin/callLog/loadCallLog", admin_token, params)
        return data_of(r).get("totalCount") if code_of(r) == 0 else "ERR:%s" % code_of(r)

    check("筛选 callType=1 单聊 → 10", cnt({"callType": 1}) == 10, cnt({"callType": 1}))
    check("筛选 callType=2 群呼 → 8", cnt({"callType": 2}) == 8, cnt({"callType": 2}))
    check("筛选 mediaType=1 音频 → 6", cnt({"mediaType": 1}) == 6, cnt({"mediaType": 1}))
    check("筛选 mediaType=2 音视频 → 12", cnt({"mediaType": 2}) == 12, cnt({"mediaType": 2}))
    check("筛选 status=2 未接 → 3", cnt({"status": 2}) == 3, cnt({"status": 2}))
    check("筛选 callerId → %d" % TOTAL, cnt({"callerId": FIX_CALLER}) == TOTAL, cnt({"callerId": FIX_CALLER}))
    check("筛选 groupId → 8", cnt({"groupId": FIX_GROUP}) == 8, cnt({"groupId": FIX_GROUP}))
    check("筛选 peerId → 10", cnt({"peerId": FIX_PEER}) == 10, cnt({"peerId": FIX_PEER}))
    check("组合筛选 callType=1+mediaType=1+status=1 → 5",
          cnt({"callType": 1, "mediaType": 1, "status": 1}) == 5,
          cnt({"callType": 1, "mediaType": 1, "status": 1}))
    check("时间窗（now-1h ~ now）→ 17（40天前行被排除）",
          cnt({"startTime": now - 3600000, "endTime": now}) == 17,
          cnt({"startTime": now - 3600000, "endTime": now}))
    check("未来起点（startTime=now+1h）→ 0", cnt({"startTime": now + 3600000}) == 0,
          cnt({"startTime": now + 3600000}))
    check("边界 startTime=create_time 恰等含边界 → 命中该行",
          cnt({"startTime": min_recent, "endTime": min_recent}) == 1,
          cnt({"startTime": min_recent, "endTime": min_recent}))
    check("40天前行按 create_time 可单独圈出 → 1",
          cnt({"startTime": old_ct, "endTime": old_ct}) == 1,
          cnt({"startTime": old_ct, "endTime": old_ct}))

    # ── 5. 注入防御 + 权限 ─────────────────────────────────
    out("\n=== 5. orderBy 注入与权限 ===")
    evil = "id desc; DROP TABLE call_log"
    total_e, lst_e = load_logs(admin_token, {"pageNo": 1, "pageSize": 50, "orderBy": evil})
    check("恶意 orderBy 请求仍正常返回 %d 条" % TOTAL,
          total_e == TOTAL and len(lst_e) == TOTAL, "total=%s" % total_e)
    check("call_log 表未被注入破坏（表仍在）",
          sql1("SELECT COUNT(*) FROM information_schema.tables "
               "WHERE table_schema='easychat' AND table_name='call_log';") == "1")
    ids_e = [x.get("id") for x in lst_e]
    check("注入后仍按 id desc 排序（服务端覆盖生效）",
          bool(ids_e) and ids_e == sorted(ids_e, reverse=True))
    total_s, _ = load_logs(admin_token, {"orderBy": "sleep(3)"})
    check("orderBy=sleep(3) 被覆盖未执行（正常快速返回）", total_s == TOTAL, "total=%s" % total_s)

    s0, r0 = http_req("/admin/callLog/loadCallLog", token=None, fields={})
    check("无 token → HTTP 401 + body 2001 登录超时",
          s0 == 401 and code_of(r0) == 2001, "HTTP=%s code=%s" % (s0, code_of(r0)))
    s1, r1 = http_req("/admin/callLog/loadCallLog", token=user_token, fields={})
    check("非管理员 → HTTP 400 + body 1003（checkAdmin 拦截）",
          s1 == 400 and code_of(r1) == 1003, "HTTP=%s code=%s" % (s1, code_of(r1)))

    # ── 6. 清理 + 回归基线 ─────────────────────────────────
    out("\n=== 6. 清理 fixture ===")
    postcleanup()
    check("SMOKE 通话记录已清空",
          sql1("SELECT COUNT(*) FROM call_log WHERE caller_id IN ('%s','%s');" % (FIX_CALLER, FIX_GHOST)) == "0")
    check("fixture 用户/群已删",
          sql1("SELECT COUNT(*) FROM user_info WHERE user_id IN ('%s','%s','%s');" % (FIX_CALLER, FIX_PEER, FIX_GHOST)) == "0"
          and sql1("SELECT COUNT(*) FROM group_info WHERE group_id='%s';" % FIX_GROUP) == "0")
    post_real = real_baseline()
    check("真实通话记录未受影响（%d 行不变）" % pre_real, post_real == pre_real,
          "pre=%s post=%s" % (pre_real, post_real))

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
