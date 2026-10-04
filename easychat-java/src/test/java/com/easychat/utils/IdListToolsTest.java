package com.easychat.utils;

import com.easychat.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
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

    @Test
    @DisplayName("⚠ 现状 parse: 空串元素**未被忽略**，与 javadoc「忽略空项」矛盾")
    void parseKeepsEmptyStringItem() {
        // ⚠ 现状（已登记为缺陷）：javadoc 声明「忽略空项」，实测空串项**照样收进来**。
        //   根因：`StringTools.isEmpty("\"\"")` 为 false（长度 2，非空白），
        //   于是进入集合，随后 `.replaceAll("\"", "")` 把两个引号剥掉 → 留下空串。
        //
        //   ⚠ 断言必须看 size / containsEmpty，**不能看 toString**：
        //   `{""}.toString()` 与 `{}.toString()` 都是 `[]`，二者无法区分。
        //   （本轮最初就误把 `[, U002, U001]` 里的空串读成了逗号。）
        List<String> single = IdListTools.parse("[\"\"]");
        assertEquals(1, single.size(), "空串项被收进来了");
        assertTrue(single.contains(""), "收到的空串项内容为空串");

        List<String> middle = IdListTools.parse("[\"U001\",\"\",\"U002\"]");
        assertEquals(3, middle.size(), "中间空串项也被收进来了");
        assertTrue(middle.contains(""));

        // 两个空串元素会被去重成一个
        assertEquals(1, IdListTools.parse("[\"\",\"\"]").size());
    }

    @Test
    @DisplayName("⚠ 现状 parse: 元素空白不被 trim，纯空白元素整体保留")
    void parseDoesNotTrimElementWhitespace() {
        // ⚠ 现状（已登记为缺陷）：元素只做了 `.trim()` 后再判空，
        //   但 trim 结果**未被写回**，故 `"   "` 以 3 个空格的形式成为一个「用户 id」。
        List<String> r = IdListTools.parse("[\"U001\",\"   \",\"U002\"]");
        assertEquals(3, r.size());
        assertTrue(r.contains("   "), "纯空白元素以原样保留");
        assertFalse(r.contains(""), "本用例确认与上一条不同：此处不是空串而是空白串");
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
        // 这条用例锁定两者的**分工**：parse 用于读旧数据（必须容错），
        // validate 用于写新数据（必须严格）。若有人把 validate 改成复用 parse，
        // 脏数据就能落库，本用例即转红。
        String dirty = "[1]";                       // 元素是数字而非字符串
        assertEquals(asSet(java.util.Collections.singletonList("1")),
                asSet(IdListTools.parse(dirty)), "parse 把数字当字符串收下");
        assertThrows(BusinessException.class, () -> IdListTools.validate(dirty),
                "validate 必须拒绝");
    }
}
