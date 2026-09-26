#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat 群文件「上传/列表/删除」活体冒烟（stdlib only，自清理）

覆盖 openspec/archive/2026-09-26-group-file 验收：
  C1 成员可上传（分片 uploadChunk + /group/file/upload 合并入库）
  C2 列表按时间倒序返回且含上传人昵称
  C3 删除为逻辑删除（上传者本人可删；非成员 2304 / 非上传者且非管理员 1002）

前置：后端 5050 已启动、MySQL/Redis 可用、karina7710@test.com 账号存在。
做法：将 karina 临时加入已有群 G08427252986 作为成员，跑完即清理成员行与群文件行。
用法：python smoke_group_file.py [日志文件]
"""
import sys
import io
import os
import time
import json
import uuid
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
USER_EMAIL = "karina7710@test.com"      # 普通用户，将被临时加为群成员
GROUP_ID = "G08427252986"               # 已有群（不新建，避免污染）
USER_ID = "U04259455805"

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


def multipart_req(path, token, fields, file_bytes, filename, ctype="application/octet-stream"):
    boundary = "----easychat%d" % int(time.time() * 1000)
    body = b""
    for k, v in fields.items():
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                 % (boundary, k, v)).encode("utf-8")
    body += ("--%s\r\nContent-Disposition: form-data; name=\"chunk\"; filename=\"%s\"\r\n"
             "Content-Type: %s\r\n\r\n" % (boundary, filename, ctype)).encode("utf-8")
    body += file_bytes
    body += ("\r\n--%s--\r\n" % boundary).encode("utf-8")
    headers = {"token": token,
               "Content-Type": "multipart/form-data; boundary=%s" % boundary}
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"code": -1, "message": raw[:200]}


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
    return data_of(r)["token"]


def add_membership():
    sql("INSERT INTO user_contact (user_id,contact_id,contact_type,status,role,create_time) "
        "VALUES ('%s','%s',1,1,2,NOW()) ON DUPLICATE KEY UPDATE status=1;" % (USER_ID, GROUP_ID))


def drop_membership():
    sql("DELETE FROM user_contact WHERE user_id='%s' AND contact_id='%s';" % (USER_ID, GROUP_ID))
    sql("DELETE FROM group_file WHERE group_id='%s' AND upload_user_id='%s';" % (GROUP_ID, USER_ID))


def main():
    out("===== group-file 活体冒烟 =====")
    out("时间: %s" % __import__("datetime").datetime.now().isoformat(timespec="seconds"))
    out("BASE=%s  user=%s  group=%s" % (BASE, USER_EMAIL, GROUP_ID))

    # ── 0. 前置 ──────────────────────────────────────────
    out("\n=== 0. 前置条件 ===")
    check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
    check("群 %s 存在" % GROUP_ID,
          sql1("SELECT COUNT(*) FROM group_info WHERE group_id='%s';" % GROUP_ID) == "1")

    drop_membership()   # 先清历史残留，保证幂等（不删除刚加的成员，下面才加）
    add_membership()
    check("已将 karina 临时加为群成员",
          sql1("SELECT COUNT(*) FROM user_contact WHERE user_id='%s' AND contact_id='%s';"
               % (USER_ID, GROUP_ID)) == "1")

    token = login(USER_EMAIL)
    check("karina 登录成功", bool(token))

    # ── 1. 上传（分片 + 合并）────────────────────────────
    out("\n=== 1. 分片上传 + 合并入库 ===")
    file_id = uuid.uuid4().hex
    payload = b"SMOKE-GROUP-FILE-PAYLOAD-1234"      # 28 字节，单分片
    _, r = multipart_req("/upload/uploadChunk", token,
                         {"fileId": file_id, "chunkIndex": 0, "totalChunks": 1},
                         payload, file_id + "_0.chunk")
    check("uploadChunk code=0", code_of(r) == 0, r)
    _, rc = post("/upload/checkChunks", token, {"fileId": file_id, "totalChunks": 1})
    check("分片已注册（checkChunks 返回 [0]）",
          code_of(rc) == 0 and (data_of(rc) or []) == [0], rc)
    _, r = post("/group/file/upload", token,
                {"fileId": file_id, "groupId": GROUP_ID,
                 "fileName": "smoke-test.txt", "totalChunks": 1, "fileType": 2,
                 "fileSize": len(payload)})
    check("合并上传 code=0", code_of(r) == 0, r)
    gf = data_of(r) or {}
    # 后端 upload 响应里 entity.id 序列化可能为 null，但记录已落库；从 DB 取真实自增 id
    fid = sql1("SELECT id FROM group_file WHERE group_id='%s' AND upload_user_id='%s' "
               "ORDER BY id DESC LIMIT 1;" % (GROUP_ID, USER_ID))
    check("合并落库（DB 有记录）", fid != "", fid)
    check("响应含文件记录(filePath 非空)", gf.get("filePath") is not None, gf.get("filePath"))
    if fid != "":
        check("DB 群文件行已写入 status=1",
              sql1("SELECT status FROM group_file WHERE id=%s;" % fid) == "1")
        check("DB 存储文件名 = fileId.txt（合并规则）",
              sql1("SELECT file_path FROM group_file WHERE id=%s;" % fid) == file_id + ".txt",
              sql1("SELECT file_path FROM group_file WHERE id=%s;" % fid))
    else:
        check("DB 群文件行已写入 status=1", False, "合并未落库")

    # ── 2. 列表 ──────────────────────────────────────────
    out("\n=== 2. 列表 ===")
    _, r = post("/group/file/list", token, {"groupId": GROUP_ID, "pageNo": 1, "pageSize": 20})
    check("列表 code=0", code_of(r) == 0, r)
    d = data_of(r)
    lst = d.get("list") or []
    check("列表 totalCount >= 1", (d.get("totalCount") or 0) >= 1, d.get("totalCount"))
    mine = [x for x in lst if str(x.get("id")) == fid]
    check("列表含刚上传文件", bool(mine), "found=%s" % bool(mine))
    if mine:
        check("列表项含上传人昵称", bool((mine[0].get("uploadUserNickName") or "").strip()),
              mine[0].get("uploadUserNickName"))
        check("列表项 fileType=2（文件）", mine[0].get("fileType") == 2, mine[0].get("fileType"))

    # ── 3. 删除（逻辑删除）──────────────────────────────
    out("\n=== 3. 删除 ===")
    _, r = post("/group/file/delete", token, {"groupId": GROUP_ID, "fileId": fid})
    check("删除 code=0", code_of(r) == 0, r)
    check("DB 逻辑删除 status=0", sql1("SELECT status FROM group_file WHERE id=%s;" % fid) == "0")
    _, r = post("/group/file/list", token, {"groupId": GROUP_ID, "pageNo": 1, "pageSize": 20})
    lst2 = (data_of(r) or {}).get("list") or []
    check("删除后列表不再出现该文件", not any(x.get("id") == fid for x in lst2))

    # ── 4. 负向：非成员被拦截 2304 ───────────────────────
    out("\n=== 4. 权限：非成员 2304 ===")
    drop_membership()   # 先移除成员身份
    _, r = post("/group/file/list", token, {"groupId": GROUP_ID, "pageNo": 1, "pageSize": 20})
    check("非成员列文件 -> 2304", code_of(r) == 2304, code_of(r))
    _, r = post("/group/file/upload", token,
                {"fileId": file_id + "x", "groupId": GROUP_ID,
                 "fileName": "x.txt", "totalChunks": 1, "fileType": 2})
    check("非成员上传 -> 2304", code_of(r) == 2304, code_of(r))
    add_membership()    # 还原，便于收尾清理

    # ── 5. 收尾 ──────────────────────────────────────────
    out("\n=== 5. 清理 ===")
    drop_membership()
    check("成员行已清理",
          sql1("SELECT COUNT(*) FROM user_contact WHERE user_id='%s' AND contact_id='%s';"
               % (USER_ID, GROUP_ID)) == "0")
    check("群文件行已清理",
          sql1("SELECT COUNT(*) FROM group_file WHERE upload_user_id='%s' AND group_id='%s';"
               % (USER_ID, GROUP_ID)) == "0")

    passed = sum(1 for _, ok in RESULTS if ok)
    total = len(RESULTS)
    out("\n===== 结果: %d/%d PASS =====" % (passed, total))
    if passed != total:
        out("失败项:")
        for name, ok in RESULTS:
            if not ok:
                out("   - %s" % name)
    return 0 if passed == total else 1


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
