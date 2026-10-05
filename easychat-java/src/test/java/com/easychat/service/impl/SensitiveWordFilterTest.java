package com.easychat.service.impl;

import com.easychat.entity.po.SensitiveWord;
import com.easychat.exception.BusinessException;
import com.easychat.utils.StringTools;
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
 *       的词条<b>既不拦截也不打码</b>，等于不存在。而规格只定义了 1/2/3。
 *       （注：四条写入路径均有范围校验，故生产<b>不可达</b>，此处作为防御性不变式钉住。）</li>
 *   <li><b>替换曾存在顺序依赖（已于 2026-10-05 修复）</b>：第二遍原按
 *       {@code result = result.replace(...)} 逐词替换且遍历内存列表顺序，
 *       而 {@code selectByStatus} <b>无 ORDER BY</b> → 顺序由 DB 决定 →
 *       短词先被替换会使长词失去匹配机会（{@code ["ab","abcd"] + "abcd"} → {@code ***cd}
 *       而非 {@code ***}），同一词表在不同环境/不同次 reload 后输出不同。
 *       修复：{@code reload()} 内预计算「长度降序」列表，第二遍改遍历它。</li>
 * </ol>
 *
 * <p><b>测试手法</b>：{@code sensitiveWordMapper} 用动态代理注入，
 * 之后一律走<b>真实 {@code reload()}</b> 构建词库 —— 不可直接反射写
 * {@code wordList}，否则测试会绕过生产构建有序列表的那段逻辑，
 * 排序被改坏时仍全绿（假覆盖）。避免启动 Spring 与数据库
 * （与 {@code MomentCanViewTest} 同一手法）。
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
        injectMapper(svc, words);
        // ⚠ **必须走真实 reload()**，不能直接反射写 wordList —— 否则测试绕过
        //   生产构建有序列表的那段逻辑，排序被改坏时测试仍全绿（假覆盖）。
        //   mapper 由 injectMapper 注入，故 reload() 不会 NPE。
        svc.reload();
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
    @DisplayName("★★ 打码结果与词表顺序无关：互含词条两种排列产出**逐字节相同**的输出")
    void maskingIsOrderIndependent() {
        // 短词 "ab"、长词 "abcd"，内容 "abcd"：
        //   修复前：短词先 → "***cd"（cd 残留）；长词先 → "***"  ← 两种顺序输出不同
        //   修复后：两种顺序均 → "***"（长词优先）
        //
        // 修复前此用例名为 maskingIsOrderDependent，断言锁的是**缺陷行为**
        // （assertEquals("***cd", ...)）。那记录的是现象，不是契约，故随本次修复改写。
        SensitiveWordServiceImpl shortFirst = serviceWith(word("ab", 1), word("abcd", 1));
        SensitiveWordServiceImpl longFirst = serviceWith(word("abcd", 1), word("ab", 1));

        String a = shortFirst.filter("abcd");
        String b = longFirst.filter("abcd");

        assertEquals("***", a, "短词在前也应长词优先完整打码");
        assertEquals(a, b, "★ 同一词表、不同顺序产生不同输出 —— 替换阶段存在顺序依赖");
    }

    @Test
    @DisplayName("★ 长词优先：短词为长词前缀时，输出等于「只按长词替换」的结果")
    void longerWordMasksFirst() {
        SensitiveWordServiceImpl svc = serviceWith(word("abcd", 1), word("abc", 1), word("ab", 1));
        assertEquals("***", svc.filter("abcd"), "最长词应整体打码，不应残留 cd");
        assertEquals("***与***", svc.filter("abcd与abc"), "两个长词各自完整打码");
        assertEquals("***", svc.filter("abc"), "最长词不在其中时由次长词整体打码");
        // 只命中最短词时按该词打码
        assertEquals("***xx", svc.filter("abxx"), "只命中短词时按该词打码");
    }

    @Test
    @DisplayName("★ 性质断言：输出中不得残留任何**在册**敏感词作为子串")
    void noRegisteredWordSurvivesInOutput() {
        // 不硬编码「哪些词该被替换」，而是断言**性质**：
        // 任意词表 + 任意内容 → 输出 SHALL NOT 含任何在册词作为子串。
        // 注意：这**不断言**「输出不含在册词的片段」—— 长词优先后残留的片段
        //       （如上面的 cd）本身不是在册词，不构成整词泄露。
        List<SensitiveWord> dict = Arrays.asList(
                word("ab", 1), word("abcd", 1), word("bc", 2), word("x", 1), word("xy", null));
        SensitiveWordServiceImpl svc = serviceWith(dict.toArray(new SensitiveWord[0]));

        for (String content : new String[]{
                "abcd", "xabcd", "abc", "xx", "abxabcdbc", "aaa", "", "abcdabcd"}) {
            String out = svc.filter(content);
            for (SensitiveWord sw : dict) {
                String w = sw.getWord();
                assertFalse(out.contains(w),
                        "内容「" + content + "」输出「" + out + "」仍含在册词「" + w + "」");
            }
        }
    }

    @Test
    @DisplayName("★★ 任意排列一致性：3 词表取**全排列**逐一断言输出相同")
    void allPermutationsProduceSameOutput() {
        SensitiveWord[] base = {word("ab", 1), word("abcd", 2), word("bc", 1)};
        String[] contents = {"abcd", "bcabcd", "ab", "zzzabcdbczz"};

        // 以第一排列的输出为基准，其余排列必须逐字节相同
        SensitiveWordServiceImpl first = serviceWith(base);
        for (String c : contents) {
            String expected = first.filter(c);
            List<SensitiveWord[]> perms = permutations(base);
            for (int i = 1; i < perms.size(); i++) {
                SensitiveWordServiceImpl p = serviceWith(perms.get(i));
                assertEquals(expected, p.filter(c),
                        "排列 #" + i + " 下内容「" + c + "」输出与基准不一致 —— 顺序依赖未消除"
                                + "（该排列词序：" + describeOrder(perms.get(i)) + "）");
            }
        }
    }

    @Test
    @DisplayName("★ 空词 / null 词不使排序与替换抛 NPE（比较器须把它们排最后）")
    void nullAndBlankWordsSortWithoutNpe() {
        SensitiveWordServiceImpl svc = serviceWith(
                word(null, 1), word("", 2), word("abcd", 1), word("ab", 1));
        assertEquals("***", svc.filter("abcd"), "含空词时仍应长词优先完整打码");
        assertEquals("这里有***", svc.filter("这里有abcd"));
        // 全部为空词时内容原样通过
        SensitiveWordServiceImpl onlyBlank = serviceWith(word(null, 1), word("", 3));
        assertEquals("任意内容", onlyBlank.filter("任意内容"), "全空词不拦截不打码");
    }

    @Test
    @DisplayName("★ 不变式：有序列表与原始词库**元素集合恒等**（仅顺序不同）")
    @SuppressWarnings("unchecked")
    void maskingListMirrorsWordListElements() {
        SensitiveWord[] words = {word("ab", 1), word("abcd", 1), word(null, 2), word("x", 3)};
        SensitiveWordServiceImpl svc = serviceWith(words);

        List<SensitiveWord> raw = readField(svc, "wordList");
        List<SensitiveWord> masked = readField(svc, "maskingList");

        assertEquals(raw.size(), masked.size(), "两个列表元素数应一致");
        List<String> rawIds = ids(raw);
        List<String> maskedIds = ids(masked);
        Collections.sort(rawIds);
        Collections.sort(maskedIds);
        assertEquals(rawIds, maskedIds, "两个列表元素集合应恒等（仅顺序不同）");
    }

    @Test
    @DisplayName("★ reload 与 filter 并发时不出现撕裂状态（有序列表须与词库成对发布）")
    void reloadDoesNotExposeTornState() throws InterruptedException {
        // 两个字段若分两次赋值，读线程可能看到「新 wordList + 旧 maskingList」。
        // 本用例用「单线程反复 reload + 多线程 filter」压测并断言：
        //   输出要么是「长词优先」的 ***，要么是「旧行为」的 ***cd，
        //   但**不得出现第三种**（既非完整打码也非旧行为 = 撕裂）。
        SensitiveWord[] words = {word("ab", 1), word("abcd", 1)};
        SensitiveWordServiceImpl svc = serviceWith(words);
        final SensitiveWordServiceImpl target = svc;
        final String allowed1 = "***";
        final String allowed2 = "***cd";
        final boolean[] torn = {false};

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                target.reload();
            }
        });
        Thread[] readers = new Thread[4];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                for (int k = 0; k < 5000; k++) {
                    String out = target.filter("abcd");
                    if (!allowed1.equals(out) && !allowed2.equals(out)) {
                        torn[0] = true;
                    }
                }
            });
        }
        writer.start();
        for (Thread r : readers) {
            r.start();
        }
        writer.join();
        for (Thread r : readers) {
            r.join();
        }
        assertFalse(torn[0], "观察到撕裂状态：输出既非长词优先结果也非旧行为");
    }

    @Test
    @DisplayName("★ 不变式：maskingList 的排序形态 = 空词在尾 + 非空长度降序 + 同长字面量升序")
    @SuppressWarnings("unchecked")
    void maskingListSortOrderInvariant() {
        // ⚠ **白盒断言**：直接读 maskingList 检查排序形态，而非只看 filter 的输出。
        //   原因：空词在 filter 里恒被 `isEmpty → continue` 跳过，故「空词排最后」
        //   这一条**在任何输入下都不可从输出观测** —— 变异把空词键从 -1 改成
        //   MAX_VALUE（使空词排到最前）时，全部输出不变、测试全绿（实测漏网）。
        //   但 ADR-002 明确声明了「空词排最后」，故用白盒断言把该声明钉住，
        //   避免文档与实现悄悄分家。
        SensitiveWord[] words = {
                word("ab", 1), word(null, 2), word("abcd", 1), word("", 1),
                word("xy", 1), word("q", 3), word("b", null)};
        SensitiveWordServiceImpl svc = serviceWith(words);
        List<SensitiveWord> masked = readField(svc, "maskingList");

        // 1) 全部非空词在前、全部空词在后
        int lastNonEmpty = -1;
        int firstEmpty = masked.size();
        for (int i = 0; i < masked.size(); i++) {
            boolean empty = StringTools.isEmpty(masked.get(i).getWord());
            if (empty) {
                firstEmpty = Math.min(firstEmpty, i);
            } else {
                lastNonEmpty = i;
            }
        }
        assertTrue(lastNonEmpty < firstEmpty,
                "空词必须全部排在非空词之后，实际序列：" + describeOrder(masked));

        // 2) 非空词段：长度严格降序
        for (int i = 1; i <= lastNonEmpty; i++) {
            String prev = masked.get(i - 1).getWord();
            String cur = masked.get(i).getWord();
            assertTrue(prev.length() >= cur.length(),
                    "非空词段应长度降序，第 " + (i - 1) + "~" + i + " 项逆序：" + describeOrder(masked));
        }

        // 3) 同长度段：字面量升序（不依赖排序算法是否稳定）
        for (int i = 1; i <= lastNonEmpty; i++) {
            String prev = masked.get(i - 1).getWord();
            String cur = masked.get(i).getWord();
            if (prev.length() == cur.length()) {
                assertTrue(prev.compareTo(cur) <= 0,
                        "同长度词应字面量升序，第 " + (i - 1) + "~" + i + " 项逆序：" + describeOrder(masked));
            }
        }
    }

    // ── 排列 / 反射 辅助 ─────────────────────────────────────────────

    /** 返回 base 的全部排列（n=3 → 6 个）。 */
    private static List<SensitiveWord[]> permutations(SensitiveWord[] base) {
        List<SensitiveWord[]> out = new ArrayList<>();
        permute(base, 0, out);
        return out;
    }

    private static void permute(SensitiveWord[] arr, int k, List<SensitiveWord[]> out) {
        if (k == arr.length) {
            out.add(arr.clone());
            return;
        }
        for (int i = k; i < arr.length; i++) {
            SensitiveWord tmp = arr[k];
            arr[k] = arr[i];
            arr[i] = tmp;
            permute(arr, k + 1, out);
            SensitiveWord t2 = arr[k];
            arr[k] = arr[i];
            arr[i] = t2;
        }
    }

    private static String describeOrder(SensitiveWord[] arr) {
        StringBuilder sb = new StringBuilder();
        for (SensitiveWord sw : arr) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(sw.getWord());
        }
        return sb.toString();
    }

    private static String describeOrder(List<SensitiveWord> list) {
        return describeOrder(list.toArray(new SensitiveWord[0]));
    }

    @SuppressWarnings("unchecked")
    private static List<SensitiveWord> readField(SensitiveWordServiceImpl svc, String name) {
        try {
            Field f = SensitiveWordServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            return (List<SensitiveWord>) f.get(svc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取字段 " + name + " 失败（字段未实现或已改名？）", e);
        }
    }

    private static List<String> ids(List<SensitiveWord> list) {
        List<String> out = new ArrayList<>();
        for (SensitiveWord sw : list) {
            out.add(sw == null ? "<null元素>" : String.valueOf(sw.getWord()));
        }
        return out;
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
