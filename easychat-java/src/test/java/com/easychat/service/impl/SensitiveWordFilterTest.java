package com.easychat.service.impl;

import com.easychat.entity.po.SensitiveWord;
import com.easychat.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SensitiveWordServiceImpl#filter} 内容治理过滤测试（2026-10-05）
 *
 * <p>这是**内容安全控制**的实现：level=3 直接拦截（消息不入库不发送），
 * level=1/2 替换为 {@code ***}。过滤失效 = 敏感内容直接放行，故必须逐条钉住。
 *
 * <p>此前零测试，而它有两处语义**未在规格中定义**、只能靠实现细节维持：
 * <ol>
 *   <li><b>未定义的 level 值使词条完全失效</b>：第一遍只拦 {@code ==3}，
 *       第二遍只替换 {@code ==1 || ==2 || null}。故 {@code level=0/4/5/99}
 *       的词条<b>既不拦截也不打码</b>，等于不存在。而规格只定义了 1/2/3。</li>
 *   <li><b>替换存在顺序依赖</b>：第二遍用 {@code result = result.replace(...)}
 *       逐词替换，短词先被替换会使长词失去匹配机会，结果随词表顺序而变。</li>
 * </ol>
 *
 * <p><b>测试手法</b>：{@code wordList} 是 private volatile 字段，用反射注入，
 * 避免启动 Spring 与数据库（与 {@code MomentCanViewTest} 同一手法）。
 */
@DisplayName("SensitiveWordServiceImpl#filter — 内容治理过滤")
class SensitiveWordFilterTest {

    private static SensitiveWord word(String w, Integer level) {
        SensitiveWord sw = new SensitiveWord();
        // ⚠ w 可能为 null（生产允许词条 word 为空），故用 Objects.hashCode 而非 w.hashCode()
        sw.setId((long) java.util.Objects.hashCode(w));
        sw.setWord(w);
        sw.setLevel(level);
        sw.setStatus(1);
        sw.setDeleteFlag(0L);
        return sw;
    }

    private static SensitiveWordServiceImpl serviceWith(SensitiveWord... words) {
        SensitiveWordServiceImpl svc = new SensitiveWordServiceImpl();
        try {
            Field f = SensitiveWordServiceImpl.class.getDeclaredField("wordList");
            f.setAccessible(true);
            f.set(svc, new ArrayList<>(Arrays.asList(words)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射注入 wordList 失败（字段已改名？）", e);
        }
        return svc;
    }

    // ═══════════════════════════════════════════════════════════════
    // 空输入 / 空词库 —— 规格「空词库不拦截」
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空词库：内容原样返回，不拦截不替换")
    void emptyWordListPassesThrough() {
        SensitiveWordServiceImpl svc = serviceWith();
        assertEquals("随便什么内容", svc.filter("随便什么内容"));
        assertEquals("含 level3 的内容", svc.filter("含 level3 的内容"));
    }

    @Test
    @DisplayName("空内容：原样返回（含 null / 空串 / 纯空白）")
    void emptyContentPassesThrough() {
        SensitiveWordServiceImpl svc = serviceWith(word("违禁", 3));
        assertNull(svc.filter(null));
        assertEquals("", svc.filter(""));
        assertEquals("   ", svc.filter("   "));
    }

    @Test
    @DisplayName("空词库时即便内容含 level3 词也不拦截")
    void emptyWordListDoesNotBlockEvenLevel3() {
        // 规格明确「空词库不拦截」——这是刻意设计（词库未配时不阻断业务）。
        assertEquals("这里有违禁词", serviceWith().filter("这里有违禁词"));
    }

    // ═══════════════════════════════════════════════════════════════
    // level=3 拦截
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("level=3 命中 → 抛 CODE_2701")
    void level3Blocks() {
        SensitiveWordServiceImpl svc = serviceWith(word("违禁词", 3));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.filter("这里有违禁词哦"));
        assertEquals(2701, e.getCode());
    }

    @Test
    @DisplayName("level=3 未命中 → 放行")
    void level3NotMatchedPasses() {
        assertEquals("干净的内容", serviceWith(word("违禁词", 3)).filter("干净的内容"));
    }

    @Test
    @DisplayName("★ level=3 优先于替换：同时含 level3 与 level1/2 词时**拦截**而非打码")
    void level3TakesPrecedenceOverMasking() {
        // 两遍遍历的设计意图：第一遍先扫 level3，故含 level3 的内容根本走不到替换。
        SensitiveWordServiceImpl svc = serviceWith(word("轻度词", 1), word("违禁词", 3));
        assertThrows(BusinessException.class, () -> svc.filter("轻度词和违禁词同时出现"));
    }

    // ═══════════════════════════════════════════════════════════════
    // level=1/2 替换
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("level=1 命中 → 替换为 ***")
    void level1Masks() {
        assertEquals("这里有***哦", serviceWith(word("违禁词", 1)).filter("这里有违禁词哦"));
    }

    @Test
    @DisplayName("level=2 命中 → 替换为 ***")
    void level2Masks() {
        assertEquals("这里有***哦", serviceWith(word("违禁词", 2)).filter("这里有违禁词哦"));
    }

    @Test
    @DisplayName("多个 level1/2 词全部被替换")
    void multipleWordsAllMasked() {
        SensitiveWordServiceImpl svc = serviceWith(word("甲词", 1), word("乙词", 2));
        assertEquals("这里有***和***", svc.filter("这里有甲词和乙词"));
    }

    @Test
    @DisplayName("同一词多次出现全部被替换")
    void repeatedOccurrencesAllMasked() {
        assertEquals("*** *** ***", serviceWith(word("脏", 1)).filter("脏 脏 脏"));
    }

    @Test
    @DisplayName("level=null 的词走替换分支（不拦截）")
    void nullLevelMasks() {
        // 第一遍要求 level != null && level == 3，故 null 不拦；
        // 第二遍显式包含 level == null，故 null 会被替换。
        SensitiveWordServiceImpl svc = serviceWith(word("未分级词", null));
        assertEquals("这里有***", svc.filter("这里有未分级词"));
        assertFalse(hasThrown(svc, "这里有未分级词"), "level=null 不应触发拦截");
    }

    // ═══════════════════════════════════════════════════════════════
    // ★ 未定义的 level 值 —— 此前无规格、无测试
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 未定义的 level（0/4/5/99）→ 既不拦截也不打码，词条完全失效")
    void undefinedLevelMakesWordIneffective() {
        for (Integer lvl : Arrays.asList(0, 4, 5, 6, 99, -1)) {
            SensitiveWordServiceImpl svc = serviceWith(word("敏感内容", lvl));
            assertEquals("这里有敏感内容", svc.filter("这里有敏感内容"),
                    "level=" + lvl + " 未在规格中定义，当前实现对其**完全忽略**"
                            + "（第一遍只拦 ==3，第二遍只替换 ==1/==2/null）");
        }
    }

    @Test
    @DisplayName("★ 未定义 level 与已定义 level 共存时，混合内容按已定义的处理")
    void undefinedLevelMixedWithDefined() {
        // 「甲」level=5（忽略）、「乙」level=1（替换）
        SensitiveWordServiceImpl svc = serviceWith(word("甲", 5), word("乙", 1));
        assertEquals("甲和***", svc.filter("甲和乙"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 词条本身的健壮性
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("word 为空的词条被跳过（不抛不替换，且不影响其它词）")
    void blankWordSkipped() {
        SensitiveWordServiceImpl svc = serviceWith(word("", 3), word(null, 3), word("   ", 1), word("真词", 1));
        assertEquals("这里有***", svc.filter("这里有真词"));
    }

    @Test
    @DisplayName("★ 全部词条的 word 都为空时，内容原样返回")
    void allBlankWordsPassThrough() {
        SensitiveWordServiceImpl svc = serviceWith(word("", 3), word(null, 1), word("  ", 2));
        assertEquals("任何内容", svc.filter("任何内容"));
    }

    // ═══════════════════════════════════════════════════════════════
    // ★ 替换的顺序依赖
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 替换存在顺序依赖：短词先替换会使长词漏匹配（结果随词表顺序而变）")
    void maskingIsOrderDependent() {
        // 短词 "ab"、长词 "abcd"，内容 "abcd"：
        //   短词先处理 → "abcd" → "***cd"，此后 "abcd" 已不存在 → 长词漏替换 → 结果 "***cd"
        //   长词先处理 → "abcd" → "***"，短词不再命中 → 结果 "***"
        // 两种顺序下**打码范围不同**（"cd" 残留），说明实现不是顺序无关的。
        SensitiveWordServiceImpl shortFirst = serviceWith(word("ab", 1), word("abcd", 1));
        SensitiveWordServiceImpl longFirst = serviceWith(word("abcd", 1), word("ab", 1));

        String a = shortFirst.filter("abcd");
        String b = longFirst.filter("abcd");

        assertEquals("***cd", a, "短词先替换 → 长词漏匹配（cd 残留）");
        assertEquals("***", b, "长词先替换 → 完整打码");
        assertFalse(a.equals(b),
                "★ 同一词表、不同顺序产生不同输出 —— 实现存在顺序依赖。"
                        + "若词表由管理员任意排序，打码结果不可预期。"
                        + "当前影响有限（残留部分本身不是敏感词，不构成泄露），"
                        + "但「同样的词表在不同环境下打码结果不同」会使问题难以复现。");
    }

    @Test
    @DisplayName("顺序依赖不影响 level=3 拦截（第一遍与词表顺序无关）")
    void level3BlockIsOrderIndependent() {
        SensitiveWordServiceImpl a = serviceWith(word("ab", 1), word("违禁", 3));
        SensitiveWordServiceImpl b = serviceWith(word("违禁", 3), word("ab", 1));
        assertThrows(BusinessException.class, () -> a.filter("含违禁的 abcd"));
        assertThrows(BusinessException.class, () -> b.filter("含违禁的 abcd"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 词库热更（reload）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ reload 用 mapper 结果**整体替换**词库（旧词立即失效、新词立即生效）")
    void reloadReplacesWholeList() {
        // ⚠ 初版此用例**没有调用 reload()**，而是直接反射改字段 ——
        //   于是「reload 不再整体替换词库」这个变异完全逃过（实测漏网）。
        //   mapper 未注入时 reload() 会 NPE，故用 Proxy 做接口 mock 后真正走 reload()。
        SensitiveWordServiceImpl svc = serviceWith(word("旧词", 3));
        assertThrows(BusinessException.class, () -> svc.filter("这里有旧词"), "前置：旧词为 level3 应拦截");

        injectMapper(svc, word("新词", 1));
        svc.reload();

        assertEquals("这里有***", svc.filter("这里有新词"), "新词库应立即生效（热更契约）");
        // 整体替换后旧词**彻底消失** —— 既不拦截（已无 level3）也不打码（原样放行）。
        assertEquals("这里有旧词", svc.filter("这里有旧词"),
                "旧词库应已整体失效 —— 既不拦截也不打码");
    }

    @Test
    @DisplayName("reload 在 mapper 返回 null 时落空列表而非 NPE")
    void reloadToleratesNullFromMapper() {
        SensitiveWordServiceImpl svc = serviceWith(word("旧词", 3));
        injectMapper(svc, (SensitiveWord[]) null);   // mapper 返回 null
        svc.reload();
        assertEquals("任何内容", svc.filter("任何内容"), "词库应被清空为空列表而非抛异常");
    }

    @Test
    @DisplayName("★ reload 反复调用是幂等的（连续热更不叠加、不丢失）")
    void reloadIsIdempotent() {
        SensitiveWordServiceImpl svc = serviceWith();
        injectMapper(svc, word("甲", 1), word("乙", 2));
        svc.reload();
        String first = svc.filter("甲和乙");
        svc.reload();
        String second = svc.filter("甲和乙");
        assertEquals(first, second, "重复 reload 结果应一致");
        assertEquals("***和***", first);
    }

    /** 用动态代理给 `sensitiveWordMapper` 注入一个只实现 selectByStatus 的 mock。 */
    private static void injectMapper(SensitiveWordServiceImpl svc, SensitiveWord... words) {
        try {
            Field f = SensitiveWordServiceImpl.class.getDeclaredField("sensitiveWordMapper");
            f.setAccessible(true);
            f.set(svc, java.lang.reflect.Proxy.newProxyInstance(
                    SensitiveWordFilterTest.class.getClassLoader(),
                    new Class<?>[]{com.easychat.mappers.SensitiveWordMapper.class},
                    (proxy, method, args) -> {
                        if ("selectByStatus".equals(method.getName())) {
                            return words == null ? null : new ArrayList<>(Arrays.asList(words));
                        }
                        return null;
                    }));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射注入 mapper 失败", e);
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static void assertNull(Object actual) {
        org.junit.jupiter.api.Assertions.assertNull(actual);
    }

    private static boolean hasThrown(SensitiveWordServiceImpl svc, String content) {
        try {
            svc.filter(content);
            return false;
        } catch (BusinessException e) {
            return true;
        }
    }
}
