package com.easychat.utils;

import com.easychat.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名单 JSON 数组工具类测试（2026-10-04）
 *
 * <p>本类是「用户级名单」（隐私设置黑/白名单）与「单条动态可见范围」
 * （{@code MomentServiceImpl#canView}）的**唯一真源**，解析语义改动会同时波及两条链路，
 * 故需把语义逐条钉死。
 *
 * <p>⚠️ <b>断言口径说明</b>：本测试刻意<b>锁定现状而非评判对错</b>。
 * 凡带「⚠ 现状」标记的用例，其断言的是**当前实现的实际行为**，
 * 其中部分行为已被登记为缺陷（见 {@code docs/system-facts.md} §14），
 * 待独立 Change 修复后这些断言需同步更新。
 */
@DisplayName("IdListTools — 名单 JSON 解析与校验")
class IdListToolsTest {

    private static Set<String> asSet(List<String> list) {
        return new HashSet<>(list);
    }

    // ─────────────────────────────────────────────────────────────
    // parse —— 宽松解析（读自己写入的数据用）
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("parse: 空输入返回空列表，永不为 null")
    void parseEmptyInputs() {
        assertTrue(IdListTools.parse(null).isEmpty());
        assertTrue(IdListTools.parse("").isEmpty());
        assertTrue(IdListTools.parse("   ").isEmpty());
        assertTrue(IdListTools.parse("[]").isEmpty());
        assertTrue(IdListTools.parse("[  ]").isEmpty());
    }

    @Test
    @DisplayName("parse: 标准 JSON 数组")
    void parseStandardArray() {
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",\"U002\"]")));
    }

    @Test
    @DisplayName("parse: 去重")
    void parseDeduplicates() {
        assertEquals(1, IdListTools.parse("[\"U001\",\"U001\",\"U001\"]").size());
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",\"U002\",\"U001\"]")));
    }

    @Test
    @DisplayName("parse: 容忍空白与缺失方括号（宽松语义）")
    void parseIsLenient() {
        // 前后空格
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("  [\"U001\",\"U002\"]  ")));
        // 元素前后空格
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[ \"U001\" , \"U002\" ]")));
        // 无方括号（退化为按逗号切分）
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("\"U001\",\"U002\"")));
    }

    // ── D1/D2 已修：先规范化、再判空 ───────────────────────────────
    // 修复前（javadoc 声明「忽略空项」但实测不忽略）：
    //   parse('[""]')                  → [""]              size=1
    //   parse('["U001","","U002"]')    → ["", U002, U001]  size=3
    //   parse('["U001","   ","U002"]') → ["   ", U002, U001] size=3
    //
    // 根因（D1 与 D2 **同根**）：判空作用于**未剥引号的原文**，规范化在其后 ——
    //   `isEmpty("\"\"")` 长度 2 → false；`isEmpty("\"   \"")` 同理 → false。
    //   故「先 trim 判空、再剥引号」这个顺序根本拦不住任何空项。
    // 修复：先规范化（trim → 剥引号 → 再 trim），再判空。
    //
    // ⚠ 断言必须看 size，**不能看 toString**：`{""}.toString()` 与 `{}.toString()`
    //   都是 `[]`，二者无法区分。（本轮最初就误把 `[, U002, U001]` 里的空串读成了逗号。）

    @Test
    @DisplayName("parse: 空串元素被忽略（修复前会产出一个空串 id）")
    void parseSkipsEmptyStringItem() {
        assertTrue(IdListTools.parse("[\"\"]").isEmpty(), "空串元素应被忽略");
        assertEquals(0, IdListTools.parse("[\"\",\"\"]").size(), "两个空串元素应全部被忽略");
        // 对照：修复前 `["",""]` 会去重成 1 项空串
        assertFalse(IdListTools.parse("[\"\",\"\"]").contains(""),
                "空串不得作为 id 进入结果");
    }

    @Test
    @DisplayName("parse: 中间与末尾的空串元素被忽略")
    void parseSkipsEmptyItemsAroundRealIds() {
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",\"\",\"U002\"]")));
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",\"U002\",\"\"]")));
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"\",\"U001\",\"\",\"U002\",\"\"]")));
    }

    @Test
    @DisplayName("parse: 纯空白元素被忽略，不再成为「用户 id」")
    void parseSkipsBlankItems() {
        assertTrue(IdListTools.parse("[\"   \"]").isEmpty(), "纯空白元素应被忽略");
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",\"   \",\"U002\"]")));
    }

    @Test
    @DisplayName("parse: 剥引号后仍带空白也被忽略（第二次 trim 的必要性）")
    void parseSkipsBlanksRemainingAfterQuoteStripping() {
        // `"  "` 剥引号后是 `  `。若只在剥引号**之前** trim 一次，
        // 这段空白会漏进结果 —— 故规范化后必须再 trim 一次。
        assertTrue(IdListTools.parse("[\"  \"]").isEmpty(), "引号内空格的元素应被忽略");
        // ⚠️ 制表符必须用字符串拼接，不能在源码里写真实 TAB。
        //   本轮初版写成 Java 转义 "\\t"，它到达 parse 时是**字面反斜杠 + t**，
        //   于是被剥引号后剩 `\t` 两个字符（非空白）→ 产出 "t" 附近的怪 id。
        //   这是本会话第五次「写锚点/字面量时转义层级错一层」，与 AGENTS §2.1 第 8 条同源。
        assertTrue(IdListTools.parse("[\"" + "\t" + "\"]").isEmpty(), "引号内制表符的元素应被忽略");
    }

    @Test
    @DisplayName("parse: 合法元素的值被去首尾空白")
    void parseTrimsRealValues() {
        assertEquals(asSet(Collections.singletonList("U001")),
                asSet(IdListTools.parse("[\"  U001  \"]")));
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[  \"U001\"  ,  \"U002\" ]")));
    }

    @Test
    @DisplayName("parse: 宽松契约 —— 对任意脏输入都不抛异常")
    void parseNeverThrows() {
        // parse 用于读可能含历史脏数据的名单列，**永不抛异常**是刻意契约；
        // 严格校验由 validate 承担。修「忽略空项」不得把它变成严格解析。
        String[] dirty = {
                "not-a-json", "[\"U001\",", "[[\"U001\"]]", "{\"a\":1}", "]", "[",
                "null", "undefined", "NaN", "[,]", "[,,]", "[\"a\"]extra"
        };
        for (String d : dirty) {
            assertNotNull(IdListTools.parse(d), "parse 永不返回 null：" + d);
        }
        // 超长输入也不得抛（validate 会抛 CODE_1001，parse 必须容错）
        StringBuilder huge = new StringBuilder("[");
        for (int i = 0; i < 8000; i++) {
            if (i > 0) {
                huge.append(',');
            }
            huge.append("\"U").append(i).append('"');
        }
        huge.append(']');
        assertNotNull(IdListTools.parse(huge.toString()));
    }

    @Test
    @DisplayName("parse: JSON null 被忽略（StringTools.isEmpty 视字面量 \"null\" 为空）")
    void parseSkipsJsonNull() {
        // 与 StringTools.isEmpty("null") == true 一致：剥括号后得到字符串 "null"，
        // 被判为空而跳过。**不是**产生一个名为 "null" 的 id。
        assertTrue(IdListTools.parse("[null]").isEmpty());
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",null,\"U002\"]")));
    }

    @Test
    @DisplayName("parse: 结果顺序不保证（内部用 HashSet），故按集合断言")
    void parseOrderNotGuaranteed() {
        // 这条用例本身在**声明**一个契约约束：调用方不得依赖 parse 的返回顺序。
        // 若将来实现改为保序（如 LinkedHashSet），本用例仍然通过，不会误报。
        List<String> r = IdListTools.parse("[\"c\",\"a\",\"b\"]");
        assertEquals(new TreeSet<>(Arrays.asList("a", "b", "c")), new TreeSet<>(r));
    }

    // ─────────────────────────────────────────────────────────────
    // 空值字面量 —— 2026-10-13（openspec 2026-10-13-id-list-null-literal-guard，
    // 遗留 #20 治本）
    //
    // 背景：`StringTools.isEmpty` 只认**小写** `"null"`，故活库里那条字面量
    // `"NULL"`（`user_info.moment_visible_list`，非 SQL NULL）会被 `parse`
    // 当成一个**真实的用户 id** 产出。后果：白名单模式拿到 `["NULL"]` →
    // 除作者外所有人都看不到该动态（没��任何真实用户 id 会等于 "NULL"）。
    // 脏数据只是**症状**，根因是解析器这道口子，故本组用例锁的是**解析行为**。
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ parse: 大写 NULL 被剔除（StringTools.isEmpty 只认小写，故曾漏过）")
    void parseSkipsUppercaseNullLiteral() {
        assertTrue(IdListTools.parse("NULL").isEmpty(), "\"NULL\" 不应成为名单成员");
        assertTrue(IdListTools.parse("[\"NULL\"]").isEmpty());
        // 真实数据形态：单列里存的裸字面量
        assertTrue(IdListTools.parse("  NULL  ").isEmpty(), "首尾空白也应归一后再判定");
    }

    @Test
    @DisplayName("★ parse: 各种语言的空值表示一律剔除")
    void parseSkipsNullFamilyLiterals() {
        for (String junk : Arrays.asList("undefined", "UNDEFINED", "NaN", "NAN", "none", "None")) {
            assertTrue(IdListTools.parse(junk).isEmpty(), junk + " 不应成为名单成员");
            assertTrue(IdListTools.parse("[\"" + junk + "\"]").isEmpty(), junk + " 不应成为名单成员");
        }
    }

    @Test
    @DisplayName("★ parse: 大小写不敏感（Null / nUlL / Null）")
    void parseSkipsNullLiteralCaseInsensitively() {
        for (String junk : Arrays.asList("Null", "nUlL", "NULL", "null", "uNdEfInEd")) {
            assertTrue(IdListTools.parse(junk).isEmpty(), junk + " 应被识别为空值字面量");
        }
    }

    @Test
    @DisplayName("★ parse: 混合名单只保留真实 id，垃圾项被剔除")
    void parseMixedListKeepsOnlyRealIds() {
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("[\"U001\",\"NULL\",\"U002\"]")));
        assertEquals(asSet(Arrays.asList("U001", "U002")),
                asSet(IdListTools.parse("U001,None,U002")));
        assertEquals(asSet(Arrays.asList("U001")),
                asSet(IdListTools.parse("[\"U001\",\"undefined\",\"\",null,\"NaN\"]")));
    }

    @Test
    @DisplayName("★ parse: 全是垃圾时与「空输入」行为完全一致（隐私判定不得有分叉）")
    void parseAllJunkEqualsEmptyInput() {
        // 白名单分支：visible.contains(realId) 两者同为 false
        // 黑名单分支：!invisible.contains(realId) 两者同为 true
        // —— 即脏名单与空名单在 canView 里表现相同，不会造出「看似非空、
        //     实则谁都不匹配」的白名单。
        for (String junk : Arrays.asList("NULL", "None", "undefined", "NaN")) {
            assertEquals(IdListTools.parse("").size(), IdListTools.parse(junk).size(),
                    junk + " 解析结果应与空输入同规模");
            assertEquals(IdListTools.parse("[]"), IdListTools.parse(junk),
                    junk + " 解析结果应与空数组一致");
        }
    }

    @Test
    @DisplayName("parse: 合法 id 与既有宽松形态**不受**新守卫影响（回归护栏）")
    void parseRealIdsUnaffected() {
        // 既有测试数据用的是 U001（3 位），生产是 U + 11 位数字 —— 两种都必须原样通过
        assertEquals(asSet(Arrays.asList("U001", "U002")), asSet(IdListTools.parse("[\"U001\",\"U002\"]")));
        assertEquals(asSet(Arrays.asList("U01234567890", "U09876543210")),
                asSet(IdListTools.parse("[\"U01234567890\",\"U09876543210\"]")));
        assertEquals(asSet(Arrays.asList("U001", "U002")), asSet(IdListTools.parse("\"U001\",\"U002\"")));
        assertEquals(asSet(Arrays.asList("U001", "U002")), asSet(IdListTools.parse("  [ \"U001\" , \"U002\" ]  ")));
        // 名字里恰好含 null / none 子串的 id 不该被误伤（黑名单是**全等**判定）
        assertEquals(asSet(Arrays.asList("U001", "U0null1", "Unone1")),
                asSet(IdListTools.parse("[\"U001\",\"U0null1\",\"Unone1\"]")));
    }

    // ─────────────────────────────────────────────────────────────
    // serialize —— 写入侧
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("serialize: 空入参返回 null（便于「不写该列」）")
    void serializeEmptyReturnsNull() {
        assertNull(IdListTools.serialize(null));
        assertNull(IdListTools.serialize(new ArrayList<>()));
    }

    @Test
    @DisplayName("serialize: 产出合法 JSON 数组且已去重")
    void serializeProducesJsonArray() {
        String json = IdListTools.serialize(Arrays.asList("U001", "U002", "U001"));
        assertNotNull(json);
        assertTrue(json.startsWith("["), "应为 JSON 数组，实际：" + json);
        assertTrue(json.endsWith("]"), "应为 JSON 数组，实际：" + json);
        // 去重后应只剩 2 项
        assertFalse(json.contains("U001,U001"), "不应含重复项：" + json);
        assertEquals(2, IdListTools.parse(json).size());
    }

    @Test
    @DisplayName("serialize: 往返一致（serialize → parse → 集合相同）")
    void serializeParseRoundTrip() {
        List<String> ids = Arrays.asList("U001", "U002", "U003");
        assertEquals(new HashSet<>(ids), asSet(IdListTools.parse(IdListTools.serialize(ids))));
    }

    @Test
    @DisplayName("serialize: 超过 MAX_LENGTH 抛 CODE_1001（防止撑爆 TEXT 列）")
    void serializeRejectsOversized() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 7000; i++) {
            tooMany.add("U" + String.format("%09d", i)); // 每项 10 字符
        }
        // ⚠ 前提自检**不能**调用 serialize 本身——它正是会抛异常的那个方法。
        //   本轮初版把长度断言写成 serialize(...).length() > MAX_LENGTH，
        //   结果在「确认超限」这一步就抛了，断言根本走不到。
        int expectedLen = com.alibaba.fastjson.JSON
                .toJSONString(new ArrayList<>(new HashSet<>(tooMany))).length();
        assertTrue(expectedLen > IdListTools.MAX_LENGTH,
                "前提不成立：构造的名单未超限（" + expectedLen + " <= " + IdListTools.MAX_LENGTH + "）");

        BusinessException e = assertThrows(BusinessException.class,
                () -> IdListTools.serialize(tooMany));
        assertEquals(1001, e.getCode());
    }

    @Test
    @DisplayName("serialize: 恰好未超限时不抛")
    void serializeAcceptsWithinLimit() {
        List<String> ok = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ok.add("U" + i);
        }
        assertNotNull(IdListTools.serialize(ok));
    }

    // ─────────────────────────────────────────────────────────────
    // validate —— 严格校验（写入侧）
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate: 未提供（null/空/纯空白）视为合法")
    void validateTreatsAbsentAsValid() {
        assertTrue(IdListTools.validate(null));
        assertTrue(IdListTools.validate(""));
        assertTrue(IdListTools.validate("    "));
    }

    @Test
    @DisplayName("validate: 合法数组通过")
    void validateAcceptsValidArrays() {
        assertTrue(IdListTools.validate("[]"));
        assertTrue(IdListTools.validate("[\"U001\"]"));
        assertTrue(IdListTools.validate("[\"U001\",\"U002\"]"));
        assertTrue(IdListTools.validate("  [\"U001\"]  "));
    }

    @Test
    @DisplayName("validate: 非法 JSON 抛 CODE_1001（必须在落库前拒绝脏数据）")
    void validateRejectsMalformedJson() {
        // 与 parse 的宽松形成对照：这些输入 parse 会「猜」，validate 必须拒绝
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("not-a-json")).getCode());
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("[\"U001\",")).getCode());
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("{\"U001\":1}")).getCode(),
                "对象而非数组必须被拒");
    }

    @Test
    @DisplayName("validate: 元素非字符串或为空串一律拒绝")
    void validateRejectsBadElements() {
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("[1]")).getCode(), "数字元素必须被拒");
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("[null]")).getCode(), "null 元素必须被拒");
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("[\"\"]")).getCode(), "空串元素必须被拒");
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("[\"   \"]")).getCode(), "纯空白元素必须被拒");
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate("[[\"U001\"]]")).getCode(), "嵌套数组必须被拒");
    }

    @Test
    @DisplayName("validate: 超长抛 CODE_1001")
    void validateRejectsOversized() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 7000; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"U").append(String.format("%09d", i)).append('"');
        }
        sb.append(']');
        assertTrue(sb.length() > IdListTools.MAX_LENGTH, "前提不成立：未超限");
        assertEquals(1001, assertThrows(BusinessException.class,
                () -> IdListTools.validate(sb.toString())).getCode());
    }

    @Test
    @DisplayName("validate 与 parse 的口径差异：同一脏数据 parse 放行、validate 拒绝")
    void validateIsStricterThanParse() {
        // 锁定两者的**分工**：parse 用于读旧数据（必须容错），
        // validate 用于写新数据（必须严格）。若有人把 validate 改成复用 parse，
        // 脏数据就能落库，本用例即转红。
        String dirty = "[1]";                       // 元素是数字而非字符串
        assertEquals(asSet(Collections.singletonList("1")),
                asSet(IdListTools.parse(dirty)), "parse 把数字当字符串收下");
        assertThrows(BusinessException.class, () -> IdListTools.validate(dirty),
                "validate 必须拒绝");
    }

    // ── 读写两侧的空项口径必须一致（design.md §2 对照表）─────────────
    // 修复前 parse 会把空串/空白产出成一个「id」，而 validate 拒绝它们 ——
    // 即「写得进、读不出」的静默不一致来源。修复后两者对空项的判断一致。

    @Test
    @DisplayName("★ validate 拒绝的空项输入，parse 一律产出空")
    void validateRejectsWhatParseEmpties() {
        String[][] cases = {
                {"[\"\"]", "单个空串"},
                {"[\"\",\"\"]", "两个空串"},
                {"[\"   \"]", "单个纯空白"},
                {"[\"U001\",\"   \",\"U002\"]", "中间夹纯空白"},
                {"[\"U001\",\"\",\"U002\"]", "中间夹空串"},
                {"[\"  \"]", "引号内空格"}
        };
        for (String[] c : cases) {
            assertThrows(BusinessException.class, () -> IdListTools.validate(c[0]),
                    "validate 应拒绝：" + c[1] + " " + c[0]);
            List<String> parsed = IdListTools.parse(c[0]);
            assertFalse(parsed.contains(""), c[1] + "：parse 产出了空串 id");
            assertFalse(parsed.contains("   "), c[1] + "：parse 产出了空白 id");
            for (String item : parsed) {
                assertNotNull(item);
                assertTrue(!item.trim().isEmpty(), c[1] + "：parse 产出了空白项 <" + item + ">");
            }
        }
    }

    @Test
    @DisplayName("★ 合法名单：validate 通过且 parse 产出完整集合")
    void validListRoundTripsBothWays() {
        String good = "[\"U001\",\"U002\"]";
        assertTrue(IdListTools.validate(good));
        assertEquals(asSet(Arrays.asList("U001", "U002")), asSet(IdListTools.parse(good)));
    }
}
