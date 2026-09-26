#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EasyChat 管理端「敏感词库管理」活体冒烟（stdlib only，不入库）
覆盖 openspec/changes/2026-09-26-sensitive-word-admin/tasks.md 验收标准：
  C1 筛选/新增/编辑/逻辑删除 + 重复2704 + 不存在2705
  C2 txt/csv 导入三态计数（success/skipped/failed）+ 重复不入库 + 超限/扩展名 1001
  C3 导出 CSV（UTF-8 BOM + 公式注入防护）且可原样导回（往返等价）
  C4 写后自动热更：level3 拦截 2701 / level2 替换 ***/ 删除即刻失效
  C5 逻辑删除与唯一性共存：删后可重新增、可重导入
  权限：非管理员 404、无 token 901

前置：后端 5050 已启动、migration-007 已执行。
用法：python smoke_sensitive_word.py [日志文件]
"""
import sys
import io
import json
import time
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
ADMIN_EMAIL = "test@qq.com"           # 白名单管理员
USER_EMAIL = "karina7710@test.com"    # 非管理员

P = "SMOKESW-"                        # fixture 词前缀（全部用例以此开头）
MOMENT_MARK = "SMOKESW-MOMENT"        # fixture 朋友圈内容前缀

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


def _read_json(resp):
    return json.loads(resp.read().decode("utf-8"))


def http_req(path, token=None, fields=None, get=False, raw=False):
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
            body = r.read()
            if raw:
                return r.status, body
            return r.status, json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        if raw:
            return e.code, body.encode("utf-8")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"code": -1, "message": body[:200]}


def multipart_req(path, token, fields, file_bytes, filename, ctype="text/plain"):
    boundary = "----easychat%d" % int(time.time() * 1000)
    body = b""
    for k, v in fields.items():
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                 % (boundary, k, v)).encode("utf-8")
    body += ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
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
    if isinstance(res, tuple) and len(res) == 2:   # 兼容 (status, json) 未解包的调用
        res = res[1]
    return res.get("code") if isinstance(res, dict) else None


def data_of(res):
    if isinstance(res, tuple) and len(res) == 2:
        res = res[1]
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


def cleanup():
    """清掉历史与本次 smoke fixture 词 + fixture 朋友圈（公式词以 = 开头，用包含匹配）"""
    sql("DELETE FROM sensitive_word WHERE word LIKE '%%%s%%';" % P)
    sql("DELETE FROM moment WHERE content LIKE '%s%%';" % MOMENT_MARK)


def count_alive(word):
    return int(sql1("SELECT COUNT(*) FROM sensitive_word WHERE word='%s' AND delete_flag=0;" % word) or 0)


def count_all(word):
    return int(sql1("SELECT COUNT(*) FROM sensitive_word WHERE word='%s';" % word) or 0)


def load_list(token, **kw):
    params = {"pageNo": 1, "pageSize": 50}
    params.update(kw)
    _, r = post("/admin/sensitiveWord/loadWord", token, params)
    if code_of(r) != 0:
        return None
    return data_of(r)


def main():
    out("===== sensitive-word-admin 活体冒烟 =====")
    out("时间: %s" % __import__("datetime").datetime.now().isoformat(timespec="seconds"))
    out("BASE=%s  admin=%s  user=%s" % (BASE, ADMIN_EMAIL, USER_EMAIL))

    # ── 0. 前置条件 ────────────────────────────────────────
    out("\n=== 0. 前置条件 ===")
    check("后端 /account/checkCode 可达", code_of(http_req("/account/checkCode", get=True)[1]) == 0)
    check("migration-007 delete_flag 字段存在",
          sql1("SELECT COUNT(*) FROM information_schema.columns "
               "WHERE table_schema='easychat' AND table_name='sensitive_word' "
               "AND column_name='delete_flag';") == "1")
    check("migration-007 uk_word_flag 唯一索引存在(word,delete_flag)",
          sql1("SELECT COUNT(*) FROM information_schema.statistics "
               "WHERE table_schema='easychat' AND table_name='sensitive_word' "
               "AND index_name='uk_word_flag' AND seq_in_index=1 AND column_name='word';") == "1"
          and sql1("SELECT COUNT(*) FROM information_schema.statistics "
                   "WHERE table_schema='easychat' AND table_name='sensitive_word' "
                   "AND index_name='uk_word_flag' AND seq_in_index=2 AND column_name='delete_flag';") == "1")
    check("旧 uk_word 索引已移除",
          sql1("SELECT COUNT(*) FROM information_schema.statistics "
               "WHERE table_schema='easychat' AND table_name='sensitive_word' "
               "AND index_name='uk_word';") == "0")

    cleanup()
    admin = login(ADMIN_EMAIL)
    user = login(USER_EMAIL)
    out("   admin/user 登录成功")

    # ── 1. C1 筛选与增改删 ─────────────────────────────────
    out("\n=== 1. C1 分页筛选 / 新增 / 编辑 / 逻辑删除 ===")
    w1 = P + "BAN"
    r = post("/admin/sensitiveWord/saveWord", admin,
             {"word": w1, "level": 3, "status": 1})
    check("新增词条 code=0", code_of(r) == 0, r)
    check("新增后库内存在且 delete_flag=0", count_alive(w1) == 1)

    r = post("/admin/sensitiveWord/saveWord", admin,
             {"word": w1, "level": 3, "status": 1})
    check("重复新增返回 2704", code_of(r) == 2704, r)

    wid = int(sql1("SELECT id FROM sensitive_word WHERE word='%s' AND delete_flag=0;" % w1))
    r = post("/admin/sensitiveWord/saveWord", admin,
             {"id": wid, "word": w1, "level": 2, "status": 0})
    check("编辑词条（改级别/状态）code=0", code_of(r) == 0, r)
    check("编辑已落库 level=2 status=0",
          sql1("SELECT CONCAT(level,'-',status) FROM sensitive_word WHERE id=%d;" % wid) == "2-0")

    w2 = P + "BAN2"
    post("/admin/sensitiveWord/saveWord", admin, {"word": w2, "level": 1, "status": 1})
    wid2 = int(sql1("SELECT id FROM sensitive_word WHERE word='%s' AND delete_flag=0;" % w2))
    r = post("/admin/sensitiveWord/saveWord", admin,
             {"id": wid2, "word": w1, "level": 1, "status": 1})
    check("编辑改名撞同名存活词条返回 2704", code_of(r) == 2704, r)

    r = post("/admin/sensitiveWord/saveWord", admin,
             {"id": 999999999, "word": P + "X", "level": 1, "status": 1})
    check("编辑不存在的词条返回 2705", code_of(r) == 2705, r)

    r = post("/admin/sensitiveWord/deleteWord", admin, {"id": 999999999})
    check("删除不存在的词条返回 2705", code_of(r) == 2705, r)

    # 筛选（此时 w1 已被编辑为 level=2 / status=0）
    lst = load_list(admin, keyword=P, level=2, status=0)
    check("筛选 keyword+level+status 命中编辑后的 w1",
          lst is not None and any(x.get("word") == w1 for x in lst.get("list", [])),
          lst.get("totalCount") if lst else None)
    check("列表只含 delete_flag=0 行（无 delete_flag 字段泄漏）",
          lst is not None and all("deleteFlag" not in x for x in lst.get("list", [])))

    # ── 2. C5 逻辑删除与唯一性共存 ─────────────────────────
    out("\n=== 2. C5 删后重新增 / 删后重导入 ===")
    r = post("/admin/sensitiveWord/deleteWord", admin, {"id": wid})
    check("删除存活词条 code=0", code_of(r) == 0, r)
    check("删除为逻辑删除（行仍在、delete_flag>0）",
          count_all(w1) == 1 and sql1("SELECT delete_flag>0 FROM sensitive_word WHERE id=%d;" % wid) == "1")
    check("删除后不再计入存活", count_alive(w1) == 0)

    r = post("/admin/sensitiveWord/saveWord", admin, {"word": w1, "level": 3, "status": 1})
    check("删后重新新增同名词 code=0（唯一索引不被已删行占用）", code_of(r) == 0, r)
    check("重新增后存活 1 行", count_alive(w1) == 1)

    # ── 3. C2 批量导入 ────────────────────────────────────
    out("\n=== 3. C2 批量导入（txt / csv / 超限 / 格式） ===")
    txt_lines = [P + "T1", P + "T2", P + "T1", "", "X" * 60, P + "T3"]
    txt = ("\n".join(txt_lines) + "\n").encode("utf-8")
    _, r = multipart_req("/admin/sensitiveWord/importWords", admin,
                         {"level": 3, "status": 1}, txt, "words.txt")
    d = data_of(r)
    check("txt 导入 code=0", code_of(r) == 0, r)
    check("txt 三态计数 success=3 skipped=1 failed=1",
          (d.get("success"), d.get("skipped"), d.get("failed")) == (3, 1, 1), d)
    check("导入词已入库且同词不重复", count_alive(P + "T1") == 1 and count_all(P + "T1") == 1)

    csvb = ("word,level,status\n%sB1,2,1\n%sB2,9,1\nbadrow\n%sB1,3,0\n%sB3,1,0\n"
            % (P, P, P, P)).encode("utf-8")
    _, r = multipart_req("/admin/sensitiveWord/importWords", admin, {}, csvb, "words.csv", "text/csv")
    d = data_of(r)
    check("csv 导入 code=0（含表头）", code_of(r) == 0, r)
    check("csv 三态计数 success=2 skipped=1 failed=2",
          (d.get("success"), d.get("skipped"), d.get("failed")) == (2, 1, 2), d)
    check("csv 级别/状态按列落库",
          sql1("SELECT level FROM sensitive_word WHERE word='%sB1' AND delete_flag=0;" % P) == "2")

    big = ("\n".join([P + "L%d" % i for i in range(5001)])).encode("utf-8")
    _, r = multipart_req("/admin/sensitiveWord/importWords", admin, {"level": 1, "status": 1},
                         big, "big.txt")
    check("超过 5000 行返回 1001", code_of(r) == 1001, r)

    _, r = multipart_req("/admin/sensitiveWord/importWords", admin, {"level": 1, "status": 1},
                         b"hello", "note.md")
    check("不支持的扩展名返回 1001", code_of(r) == 1001, r)

    _, r = multipart_req("/admin/sensitiveWord/importWords", admin, {"level": 1, "status": 1},
                         b"a" * (2 * 1024 * 1024 + 10), "huge.txt")
    check("超过 2MB 返回 1001", code_of(r) == 1001, r)

    # ── 4. C3 导出与往返 ──────────────────────────────────
    out("\n=== 4. C3 导出 CSV（BOM + 公式注入防护）与往返 ===")
    fw = "=" + P + "SUM"           # 必须以 = 开头才触发导出公式注入防护
    post("/admin/sensitiveWord/saveWord", admin, {"word": fw, "level": 1, "status": 1})
    # 导出前删掉 w2，验证已删词不出现在导出中
    post("/admin/sensitiveWord/deleteWord", admin, {"id": wid2})
    st, body = http_req("/admin/sensitiveWord/exportWords", token=admin, get=True, raw=True)
    text = body.decode("utf-8-sig")
    lines = text.splitlines()
    check("导出 HTTP 200", st == 200, st)
    check("导出含 UTF-8 BOM", body[:3] == b"\xef\xbb\xbf", body[:6])
    check("导出表头 word,level,status", lines[0].strip() == "word,level,status", lines[:1])
    check("已删词条不在导出中", not any(w2 in ln for ln in lines),
          [ln for ln in lines if w2 in ln][:1])
    check("存活词条（含重新新增的 w1）只出现一行", sum(1 for ln in lines if ln.startswith(w1 + ",")) == 1,
          [ln for ln in lines if ln.startswith(w1 + ",")])
    check("公式注入防护：= 开头词被前置单引号", any(ln.startswith("'=") for ln in lines),
          [ln for ln in lines if ln.startswith("'=")][:1])
    check("导出列数固定为 3（无 delete_flag 字段）",
          all(len(ln.split(",")) == 3 for ln in lines if ln), lines[:3])

    # 往返：原样导回应全部 skipped（均已存在），词库不变
    before = sql1("SELECT COUNT(*) FROM sensitive_word WHERE delete_flag=0;")
    _, r = multipart_req("/admin/sensitiveWord/importWords", admin, {}, body, "roundtrip.csv", "text/csv")
    d = data_of(r)
    after = sql1("SELECT COUNT(*) FROM sensitive_word WHERE delete_flag=0;")
    check("导出文件原样导回 success=0（全部 skipped）", d.get("success") == 0, d)
    check("往返后存活词数不变", before == after, "%s -> %s" % (before, after))

    # ── 5. C4 写后热更 ────────────────────────────────────
    out("\n=== 5. C4 写后自动热更（拦截 / 替换 / 删除失效） ===")
    hb = P + "HOTBAN"
    post("/admin/sensitiveWord/saveWord", admin, {"word": hb, "level": 3, "status": 1})
    _, r = post("/moment/publish", user,
                {"content": "%s %s 测试内容" % (MOMENT_MARK, hb), "visibility": 0})
    check("新增 level3 词后发送含该词消息被拦截 2701", code_of(r) == 2701, r)
    check("被拦截内容未入库", sql1("SELECT COUNT(*) FROM moment WHERE content LIKE '%%%s%%';" % hb) == "0")

    rl = P + "HOTREPL"
    post("/admin/sensitiveWord/saveWord", admin, {"word": rl, "level": 2, "status": 1})
    mark_content = "%s %s 可发内容" % (MOMENT_MARK, rl)
    _, r = post("/moment/publish", user, {"content": mark_content, "visibility": 0})
    check("新增 level2 词后发布 code=0", code_of(r) == 0, r)
    stored = sql1("SELECT content FROM moment WHERE content LIKE '%%%s%%' ORDER BY id DESC LIMIT 1;"
                  % MOMENT_MARK)
    check("命中词被替换为 ***", stored.startswith(MOMENT_MARK) and "HOTREPL" not in stored
          and "***" in stored, stored)

    hid = int(sql1("SELECT id FROM sensitive_word WHERE word='%s' AND delete_flag=0;" % hb))
    post("/admin/sensitiveWord/deleteWord", admin, {"id": hid})
    _, r = post("/moment/publish", user,
                {"content": "%s %s 删除后可发" % (MOMENT_MARK, hb), "visibility": 0})
    check("删除 level3 词后立即失效（发布 code=0）", code_of(r) == 0, r)

    # 导入同样触发热更
    imp = P + "IMPBAN"
    _, r = multipart_req("/admin/sensitiveWord/importWords", admin, {"level": 3, "status": 1},
                         (imp + "\n").encode("utf-8"), "hot.txt")
    _, r = post("/moment/publish", user,
                {"content": "%s %s 导入即生效" % (MOMENT_MARK, imp), "visibility": 0})
    check("导入后新词立即生效（拦截 2701）", code_of(r) == 2701, r)

    # ── 6. 权限与错误码 ───────────────────────────────────
    out("\n=== 6. 权限隔离（404 / 901） ===")
    st, r = post("/admin/sensitiveWord/loadWord", user, {"pageNo": 1, "pageSize": 10})
    check("非管理员被 checkAdmin 拦截返回 404", code_of(r) == 404, (st, r))
    st, r = http_req("/admin/sensitiveWord/loadWord", fields={"pageNo": 1})
    check("无 token 返回 901", code_of(r) == 901, (st, r))
    st, r = http_req("/admin/sensitiveWord/exportWords", get=True, raw=True)
    check("无 token 导出返回 901", b"901" in r if isinstance(r, bytes) else False, str(r)[:120])
    st, r = post("/admin/sensitiveWord/deleteWord", user, {"id": 1})
    check("非管理员删除被拦截 404", code_of(r) == 404, (st, r))

    # ── 7. 收尾：清理 fixture ─────────────────────────────
    out("\n=== 7. fixture 清理 ===")
    cleanup()
    left = sql1("SELECT COUNT(*) FROM sensitive_word WHERE word LIKE '%s%%';" % P)
    check("fixture 词已清理", left == "0", left)
    left = sql1("SELECT COUNT(*) FROM moment WHERE content LIKE '%s%%';" % MOMENT_MARK)
    check("fixture 朋友圈已清理", left == "0", left)

    # ── 汇总 ──────────────────────────────────────────────
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
        _LOGFP = io.open(sys.argv[1], "a", encoding="utf-8")
    sys.exit(main())
