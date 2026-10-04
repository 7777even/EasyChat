"""列出 Chat.vue 的消息分发分支：每个 v-if 覆盖哪些 messageType、渲染哪个子组件。

用途：为「消息类型漏渲染」这类静默失效缺陷建立可机控断言清单。
仅本机调研用，非仓库测试/门禁。
用法：python engineering/qa/_chat_branches.py
"""
import io
import re
import sys

CHAT = 'easychat-front/src/renderer/src/views/chat/Chat.vue'
s = io.open(CHAT, encoding='utf-8').read()

blocks = re.findall(r'<template\s+v-if="([\s\S]*?)"\s*>\s*<([A-Za-z0-9]+)', s)

covered = {}
out = []
for cond, comp in blocks:
    types = sorted(int(x) for x in re.findall(r'messageType\s*==\s*(\d+)', cond))
    out.append(f'{comp:26} <- {types}')
    for t in types:
        covered.setdefault(t, []).append(comp)

for line in out:
    sys.stdout.write(line + '\n')

sys.stdout.write('\n覆盖总计: %d 种\n' % len(covered))
for t in sorted(covered):
    sys.stdout.write('  %2d -> %s\n' % (t, ','.join(covered[t])))

# 后端枚举
ENUM = 'easychat-java/src/main/java/com/easychat/entity/enums/MessageTypeEnum.java'
e = io.open(ENUM, encoding='utf-8').read()
names = {}
for m in re.finditer(r'^\s*([A-Z_]+)\(\s*(\d+)\s*,', e, re.M):
    names[int(m.group(2))] = m.group(1)

sys.stdout.write('\n后端枚举总数: %d\n' % len(names))
sys.stdout.write('未被 Chat.vue 覆盖的类型:\n')
for t in sorted(names):
    if t not in covered:
        sys.stdout.write('  %2d %s\n' % (t, names[t]))