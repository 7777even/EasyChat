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

/**
 * 安全地把 `buildExtraData` 的返回值转成对象。
 *
 * <p>⚠️ **必须用它而不是 `JSON.parse`**：`buildExtraData` 在「无任何附加信息」时
 * 返回 **`null`**（而非 `'{}'`），而 `JSON.parse(null)` 会得到 `null`，
 * 再取属性即 TypeError。本轮两次踩同一个坑（单聊用例、权限剥离用例）。
 *
 * <p>这本身也是被断言的行为之一：`null` 表示**服务端拿不到 extraData**，
 * 对「普通成员手打 @所有人 被剥离」而言正是期望结果。
 */
function extraOf (json) {
  if (json === null || json === undefined) {
    return {}
  }
  return JSON.parse(json)
}

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

  // ── 缺陷 1（已修复）────────────────────────────────────────────
  // 修复前：`(item.contactName || '').toLowerCase().includes(kw)`
  //   ⇒ `filterMembers(list, 'U002')` 返回 `[]`
  //   （面板显示用的是 `contactName || userId`，故 U002 以 userId 呈现却搜不到）
  // 修复后：见 T3 组「按 userId 可搜到未设昵称的成员」。
  // 本组不再保留「现状」断言 —— 缺陷既已修复，留下相反期望的断言只会
  // 让后来者无法判断哪组才是当前契约。目标行为集中在 T3 组。

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

  // ── 缺陷 3（已修复）────────────────────────────────────────────
  // 修复前：`extra.atUserIds = matched.map(item => item.substring(1))`（**不去重**），
  //   而 `buildAtUserIdsField` 走 `Array.from(new Set(...))`（**去重**）——
  //   同一正则被复制两份、两处口径相反。
  // 修复后：两者统一委托 `extractAtUserIds`（去重），见 T3 组。

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

// ============================================================================
// T3 目标行为（缺陷修复）
//
// 上面的 ⚠ 用例锁定的是**缺陷修复前**的行为，保留作为「缺陷曾存在」的证据。
// 本组断言**修复后**的目标行为（openspec 2026-10-04-at-mention-pure-core spec-delta）。
// 两组对同一函数给出相反期望，是刻意的：修复时须**同时**改代码与把 ⚠ 用例改写为
// 「修复前 = X」的显式记录，否则后来者无法判断哪组才是当前契约。
// ============================================================================

describe('T3 目标行为 — 群成员搜索与显示口径一致（缺陷 1）', () => {
  const list = [
    { userId: 'U001', contactName: '张三' },
    { userId: 'U002', contactName: '' },
    { userId: 'U003', contactName: 'LiSi' }
  ]

  it('按 userId 可搜到未设昵称的成员（此前搜不到）', () => {
    expect(filterMembers(list, 'U002').map((i) => i.userId)).toEqual(['U002'])
  })

  it('按 userId 片段亦可命中', () => {
    expect(filterMembers(list, 'U00').map((i) => i.userId)).toEqual(['U001', 'U002', 'U003'])
  })

  it('按昵称搜索行为不变', () => {
    expect(filterMembers(list, '张').map((i) => i.userId)).toEqual(['U001'])
    expect(filterMembers(list, 'li').map((i) => i.userId)).toEqual(['U003'])
  })

  it('昵称与 userId 任一命中即保留（OR 语义）', () => {
    const mixed = [
      { userId: 'U777', contactName: '王五' },
      { userId: 'zhangsan', contactName: '' }
    ]
    expect(filterMembers(mixed, '王').map((i) => i.userId)).toEqual(['U777'])
    expect(filterMembers(mixed, 'zhang').map((i) => i.userId)).toEqual(['zhangsan'])
  })

  it('首尾空白与大小写不敏感', () => {
    expect(filterMembers(list, '  ZHANGSAN  ')).toEqual([])
    expect(filterMembers(list, '  张  ').map((i) => i.userId)).toEqual(['U001'])
    expect(filterMembers(list, '  LIS  ').map((i) => i.userId)).toEqual(['U003'])
  })
})

describe('T3 目标行为 — atAll 写入以角色权限为准（缺陷 2）', () => {
  it('普通成员(2)手工键入 @所有人 ⇒ 不写 atAll，消息正常发出', () => {
    const json = buildExtraData({
      contactType: 1,
      messageContent: '大家好 @所有人',
      atAllEnabled: false,
      role: 2
    })
    const extra = extraOf(json)
    expect(extra.atAll).toBeUndefined()
    // 正文既无 @Uxxx 又无（被剥离的）@所有人 ⇒ 没有任何附加信息 ⇒ extraData 为 null。
    // 这正是「标记不生效、消息正常送达」的实现形态。
    expect(json).toBeNull()
  })

  it('普通成员即使面板勾选也不写 atAll', () => {
    const extra = extraOf(
      buildExtraData({ contactType: 1, messageContent: '大家好', atAllEnabled: true, role: 2 })
    )
    expect(extra.atAll).toBeUndefined()
  })

  it('群主(0)面板勾选 ⇒ 写 atAll', () => {
    const extra = JSON.parse(
      buildExtraData({ contactType: 1, messageContent: '大家好', atAllEnabled: true, role: 0 })
    )
    expect(extra.atAll).toBe(true)
  })

  it('★ 管理员(1)草稿重发（atAllEnabled 丢失、正文仍含 @所有人）⇒ 仍写 atAll', () => {
    // 兜底分支必须保留：草稿只存文本，atAllEnabled 是运行时 ref 不持久化。
    // 若为修缺陷 2 而删掉「正文含 @所有人 也认」，草稿重发就会丢标记。
    const extra = JSON.parse(
      buildExtraData({
        contactType: 1,
        messageContent: '大家好 @所有人',
        atAllEnabled: false,
        role: 1
      })
    )
    expect(extra.atAll).toBe(true)
  })

  it('角色未知（undefined）时保守视为无权限', () => {
    const extra = extraOf(
      buildExtraData({
        contactType: 1,
        messageContent: '大家好 @所有人',
        atAllEnabled: false,
        role: undefined
      })
    )
    expect(extra.atAll).toBeUndefined()
  })

  it('普通成员手打 @所有人 时 atUserIds 仍正常提取（消息本身不受阻）', () => {
    const extra = JSON.parse(
      buildExtraData({
        contactType: 1,
        messageContent: '@U001 大家好 @所有人',
        atAllEnabled: false,
        role: 2
      })
    )
    expect(extra.atUserIds).toEqual(['U001'])
    expect(extra.atAll).toBeUndefined()
  })
})

describe('T3 目标行为 — atUserIds 口径统一且去重（缺陷 3）', () => {
  it('extraData.atUserIds 去重', () => {
    const extra = JSON.parse(
      buildExtraData({ contactType: 1, messageContent: '@U001 @U001 @U002' })
    )
    expect(extra.atUserIds).toEqual(['U001', 'U002'])
  })

  it('★ extraData.atUserIds 与 atUserIds 字段口径完全一致', () => {
    // 这是缺陷 3 的核心断言：两处曾各自复制同一正则、且去重口径相反。
    for (const content of ['@U001 @U001', '@U001 @U002 @U001', '@U001 纯文本 @U002 @U002']) {
      const extra = JSON.parse(buildExtraData({ contactType: 1, messageContent: content }))
      const field = buildAtUserIdsField(content)
      expect(extra.atUserIds, `content=${content}`).toEqual(field.split(','))
    }
  })

  it('extraData.atUserIds 为去重后的**数组**（非逗号串）', () => {
    const extra = JSON.parse(buildExtraData({ contactType: 1, messageContent: '@U001 @U002' }))
    expect(Array.isArray(extra.atUserIds)).toBe(true)
  })
})
