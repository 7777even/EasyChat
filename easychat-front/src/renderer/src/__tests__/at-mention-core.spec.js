import { describe, it, expect } from 'vitest'
import {
  AT_ALL_TEXT,
  AT_ALL_ROLE,
  AT_ADMIN_ROLE,
  canAtAll,
  roleText,
  filterMembers,
  spliceAtText,
  extractAtUserIds,
  buildExtraData,
  buildAtUserIdsField
} from '@/utils/atMentionCore.mjs'

// ============================================================================
// 等价性基线测试（T2 阶段）
//
// 本文件在 T2 阶段断言的是**抽离前 MessageSend.vue 的实际行为**，
// 包含三处已知缺陷的**现状**行为（标 ⚠ 的用例）。
// 目的是先证明「除三处缺陷外，抽离未改变任何行为」，
// 再在 T3 阶段用新增用例把缺陷改掉 —— 两阶段分开提交，回归可归因。
// ============================================================================

describe('T2 等价性 — 常量', () => {
  it('AT_ALL_TEXT / 角色常量与抽离前一致', () => {
    expect(AT_ALL_TEXT).toBe('@所有人')
    expect(AT_ALL_ROLE).toBe(0)
    expect(AT_ADMIN_ROLE).toBe(1)
  })
})

describe('T2 等价性 — canAtAll', () => {
  it('群主(0) 与管理员(1) 为 true', () => {
    expect(canAtAll(0)).toBe(true)
    expect(canAtAll(1)).toBe(true)
  })

  it('成员(2) 与未知值为 false', () => {
    expect(canAtAll(2)).toBe(false)
    expect(canAtAll(null)).toBe(false)
    expect(canAtAll(undefined)).toBe(false)
  })
})

describe('T2 等价性 — roleText', () => {
  it('0/1/2 映射为群主/管理员/成员', () => {
    expect(roleText(0)).toBe('群主')
    expect(roleText(1)).toBe('管理员')
    expect(roleText(2)).toBe('成员')
  })

  it('未知值兜底为「成员」（抽离前是 `|| \'成员\'`）', () => {
    expect(roleText(99)).toBe('成员')
    expect(roleText(null)).toBe('成员')
  })
})

describe('T2 等价性 — filterMembers', () => {
  const list = [
    { userId: 'U001', contactName: '张三' },
    { userId: 'U002', contactName: '' },
    { userId: 'U003', contactName: 'LiSi' }
  ]

  it('空关键词返回原列表', () => {
    expect(filterMembers(list, '')).toHaveLength(3)
    expect(filterMembers(list, null)).toHaveLength(3)
    expect(filterMembers(list, '   ')).toHaveLength(3)
  })

  it('按昵称子串匹配，大小写不敏感', () => {
    expect(filterMembers(list, '张').map((i) => i.userId)).toEqual(['U001'])
    expect(filterMembers(list, 'li').map((i) => i.userId)).toEqual(['U003'])
  })

  it('⚠ 现状：未设昵称者按 userId 搜不到（缺陷 1，T3 修）', () => {
    // 面板「显示」用的是 contactName || userId，故 U002 以 userId 呈现；
    // 但「过滤」只用 contactName，于是它看得见却搜不到。
    expect(filterMembers(list, 'U002')).toHaveLength(0)
  })

  it('null 列表返回空数组', () => {
    expect(filterMembers(null, 'a')).toEqual([])
    expect(filterMembers(undefined, 'a')).toEqual([])
  })
})

describe('T2 等价性 — spliceAtText', () => {
  it('在光标处插入并返回新光标位置', () => {
    const r = spliceAtText('ABCD', 'XY', 2, 2)
    expect(r.content).toBe('ABXYCD')
    expect(r.cursor).toBe(4)
  })

  it('替换选区（start≠end）', () => {
    const r = spliceAtText('ABCD', 'Z', 1, 3)
    expect(r.content).toBe('AZD')
    expect(r.cursor).toBe(2)
  })

  it('start/end 越界时钳制到内容长度内', () => {
    expect(spliceAtText('AB', 'X', 99, 99).content).toBe('ABX')
    expect(spliceAtText('AB', 'X', -5, -5).content).toBe('XAB')
  })

  it('start > end 时归一化为「替换二者之间的选区」，不产生重复拼接', () => {
    // DOM 本身保证 selectionStart <= selectionEnd，故此分支是防御性的。
    // 归一化语义：from=min(start,end)，to=max(start,end)，结果 = 前 + text + 后。
    // 若不做归一化，slice(0,3) + 'X' + slice(1) 会得到 'ABCXBCD' —— 文本被复制了一份。
    const r = spliceAtText('ABCD', 'X', 3, 1)
    expect(r.content).toBe('AXD')
    expect(r.cursor).toBe(2)
  })
})

describe('T2 等价性 — extractAtUserIds', () => {
  it('提取并去重（抽离前 buildAtUserIds 的口径）', () => {
    expect(extractAtUserIds('@U001 和 @U002').sort()).toEqual(['U001', 'U002'])
    expect(extractAtUserIds('@U001 @U001 @U001')).toEqual(['U001'])
  })

  it('非 U 开头的 @ 不提取', () => {
    expect(extractAtUserIds('@someone hello')).toEqual([])
  })

  it('空输入返回空数组', () => {
    expect(extractAtUserIds('')).toEqual([])
    expect(extractAtUserIds(null)).toEqual([])
    expect(extractAtUserIds(undefined)).toEqual([])
  })
})

describe('T2 等价性 — buildExtraData', () => {
  const quote = { messageId: 'M1', quoteContent: '被引用的内容', quoteNickName: '张三' }

  it('无引用无 @ 时返回 null', () => {
    expect(buildExtraData({ contactType: 0, messageContent: '你好' })).toBeNull()
  })

  it('带引用时写出三个引用字段', () => {
    const extra = JSON.parse(buildExtraData({ contactType: 0, messageContent: '你好', quoteInfo: quote }))
    expect(extra.quoteId).toBe('M1')
    expect(extra.quoteContent).toBe('被引用的内容')
    expect(extra.quoteNickName).toBe('张三')
  })

  it('引用昵称为空时落空串而非 undefined', () => {
    const extra = JSON.parse(
      buildExtraData({ contactType: 0, messageContent: '', quoteInfo: { ...quote, quoteNickName: null } })
    )
    expect(extra.quoteNickName).toBe('')
  })

  it('群聊正文含 @ 时写入 atUserIds', () => {
    const extra = JSON.parse(
      buildExtraData({ contactType: 1, messageContent: '@U001 你好' })
    )
    expect(extra.atUserIds).toEqual(['U001'])
  })

  it('单聊不写 atUserIds（即使正文含 @U001）', () => {
    // 单聊 + 无引用 ⇒ extraData 没有任何 key ⇒ 返回 null（而非 '{}'）。
    // 本轮初版写成 JSON.parse(...) 后取属性，故 NPE —— 是测试写错，不是实现错。
    expect(buildExtraData({ contactType: 0, messageContent: '@U001 你好' })).toBeNull()
  })

  it('⚠ 现状：extraData.atUserIds 不去重（缺陷 3，T3 修）', () => {
    const extra = JSON.parse(
      buildExtraData({ contactType: 1, messageContent: '@U001 @U001' })
    )
    expect(extra.atUserIds).toEqual(['U001', 'U001'])
  })

  it('⚠ 现状：正文含 @所有人 即写 atAll，与角色无关（缺陷 2，T3 修）', () => {
    // 抽离前条件是 `atAllEnabled || 正文含 @所有人`，没有叠加权限判定。
    // 普通成员手工键入即可产出 atAll=true，随后被服务端 CODE_2305 拒绝。
    const extra = JSON.parse(
      buildExtraData({ contactType: 1, messageContent: '大家好 @所有人', atAllEnabled: false, role: 2 })
    )
    expect(extra.atAll).toBe(true)
  })

  it('面板勾选 atAllEnabled 时写 atAll', () => {
    const extra = JSON.parse(
      buildExtraData({ contactType: 1, messageContent: '大家好', atAllEnabled: true, role: 0 })
    )
    expect(extra.atAll).toBe(true)
  })

  it('单聊不写 atAll', () => {
    expect(buildExtraData({ contactType: 0, messageContent: '@所有人', atAllEnabled: true })).toBeNull()
  })

  it('单聊 + 有引用时只写引用字段，不夹带任何 @ 字段', () => {
    const extra = JSON.parse(
      buildExtraData({
        contactType: 0,
        messageContent: '@所有人 @U001',
        atAllEnabled: true,
        quoteInfo: quote
      })
    )
    expect(Object.keys(extra).sort()).toEqual(['quoteContent', 'quoteId', 'quoteNickName'])
  })
})

describe('T2 等价性 — buildAtUserIdsField', () => {
  it('去重后以逗号连接', () => {
    expect(buildAtUserIdsField('@U001 @U002 @U001')).toBe('U001,U002')
  })

  it('无匹配时返回 null', () => {
    expect(buildAtUserIdsField('没有提及')).toBeNull()
    expect(buildAtUserIdsField('')).toBeNull()
    expect(buildAtUserIdsField(null)).toBeNull()
  })
})
