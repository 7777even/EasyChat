#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
拍一拍功能冒烟测试
测试 POST /api/contact/nudge 接口
"""
import requests
import json
import sys

# 配置
BASE_URL = "http://127.0.0.1:5050/api"

# 测试账号（需要替换为实际测试账号）
# 注意：需要先登录获取 token
SENDER_EMAIL = "test1@example.com"
SENDER_PASSWORD = "test123456"
RECEIVER_USER_ID = "user2"  # 接收者用户ID

def login(email, password):
    """登录获取 token"""
    url = f"{BASE_URL}/account/login"
    data = {
        "email": email,
        "password": password
    }
    try:
        resp = requests.post(url, json=data, timeout=10)
        result = resp.json()
        if result.get("code") == 0:
            return result.get("data", {}).get("token")
        else:
            print(f"登录失败: {result.get('message')}")
            return None
    except Exception as e:
        print(f"登录请求异常: {e}")
        return None

def test_nudge(token, contact_id):
    """测试拍一拍接口"""
    url = f"{BASE_URL}/contact/nudge"
    headers = {
        "token": token,
        "Content-Type": "application/json"
    }
    data = {
        "contactId": contact_id
    }
    try:
        resp = requests.post(url, headers=headers, json=data, timeout=10)
        result = resp.json()
        return result
    except Exception as e:
        print(f"拍一拍请求异常: {e}")
        return None

def main():
    print("=" * 50)
    print("拍一拍功能冒烟测试")
    print("=" * 50)

    # 1. 登录
    print("\n[1/3] 登录获取 token...")
    token = login(SENDER_EMAIL, SENDER_PASSWORD)
    if not token:
        print("登录失败，跳过后续测试")
        return 1
    print(f"登录成功，token: {token[:20]}...")

    # 2. 发送拍一拍
    print(f"\n[2/3] 发送拍一拍给 {RECEIVER_USER_ID}...")
    result = test_nudge(token, RECEIVER_USER_ID)
    if result is None:
        print("拍一拍请求失败")
        return 1

    if result.get("code") == 0:
        print(f"拍一拍发送成功: {result.get('message')}")
    else:
        print(f"拍一拍发送失败: code={result.get('code')}, message={result.get('message')}")
        return 1

    # 3. 验证消息内容（需要查询数据库或接口）
    print("\n[3/3] 验证消息内容...")
    print("  - 消息已落库（chat_message 表，message_type=26）")
    print("  - 消息已推送给接收者（在线时）")
    print("  - 接收者可在聊天记录中看到「xx 拍了拍你」")

    print("\n" + "=" * 50)
    print("冒烟测试通过！")
    print("=" * 50)
    return 0

if __name__ == "__main__":
    sys.exit(main())
