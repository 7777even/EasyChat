#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读探测：admin 标记与管理端拦截（不写库，不入库）"""
import json, hashlib, subprocess, urllib.request, urllib.parse, urllib.error

BASE = "http://localhost:5050/api"
REDIS = r"C:\Program Files\Redis\redis-cli.exe"
PWD = hashlib.md5(b"Test@123456").hexdigest()


def req(path, token=None, fields=None):
    h = {}
    if token:
        h["token"] = token
    if fields is None:
        r = urllib.request.Request(BASE + path, headers=h, method="GET")
    else:
        h["Content-Type"] = "application/x-www-form-urlencoded"
        r = urllib.request.Request(BASE + path, data=urllib.parse.urlencode(fields).encode(),
                                   headers=h, method="POST")
    try:
        with urllib.request.urlopen(r, timeout=15) as x:
            return x.status, json.loads(x.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except ValueError:
            return e.code, {"raw": body[:200]}


def login(email):
    _, r = req("/account/checkCode")
    key = r["data"]["checkCodeKey"]
    code = subprocess.run([REDIS, "GET", "easychat:checkcode:" + key],
                          capture_output=True, text=True).stdout.strip().strip('"')
    _, r = req("/account/login", fields={"email": email, "password": PWD,
                                         "checkCodeKey": key, "checkCode": code})
    d = r.get("data") or {}
    return d.get("token"), d


for email in ("test@qq.com", "karina7710@test.com"):
    tok, vo = login(email)
    print("%-28s admin=%-6s token=%s" % (email, vo.get("admin"), bool(tok)))
    for path in ("/admin/report/loadReport",):
        st, r = req(path, tok, {})
        print("    POST %-28s -> http=%s body.code=%s msg=%s"
              % (path, st, r.get("code"), r.get("message")))

# 配置键核对：AppConfig 读 admin.emails（不存在），正确键是 easychat.admin-emails
print("\nAppConfig @Value key = 'admin.emails' (default '') -> 项目配置只有 'easychat.admin-emails'")
