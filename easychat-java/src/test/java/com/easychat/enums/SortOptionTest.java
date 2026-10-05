package com.easychat.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.easychat.entity.enums.ResponseCodeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.exception.BusinessException;

/**
 * 排序白名单契约测试。
 *
 * <p>本类锁定三件事（AGENTS §2.1 第 14 条：先造反例确认能抓住，再谈覆盖）：
 * 1. 白名单内取值能解析到枚举项，且其 SQL 片段是**固定字面量**（不含任何调用方输入）
 * 2. 白名单外取值**抛 CODE_1001**（ADR-004：显式报错，**不静默回退**）
 * 3. 每张表都有默认项 —— 修掉「orderBy 为 null 时分页无 ORDER BY」的既有缺陷
 */
class SortOptionTest {

    // ── 1. 白名单内取值 ─────────────────────────────────────────

    @Test
    @DisplayName("白名单内的表+列+方向能解析到枚举项")
    void resolvesWhitelistedSort() {
        SortOption s = SortOption.fromHttp("group_info", "createTime", "desc");
        assertNotNull(s);
        assertEquals(SortOption.GROUP_INFO_CREATE_TIME_DESC, s);
        assertEquals("create_time desc", s.getSql());
    }

    @Test
    @DisplayName("方向大小写不敏感：ASC / Asc 均归一为小写 asc")
    void directionIsCaseInsensitive() {
        assertEquals(SortOption.USER_CONTACT_CREATE_TIME_ASC,
            SortOption.fromHttp("user_contact", "createTime", "ASC"));
        assertEquals(SortOption.USER_CONTACT_CREATE_TIME_ASC,
            SortOption.fromHttp("user_contact", "createTime", "Asc"));
    }

    @Test
    @DisplayName("方向为空时默认 desc（与修复前各处 setOrderBy 的主流写法一致）")
    void blankDirectionDefaultsToDesc() {
        assertEquals(SortOption.GROUP_INFO_CREATE_TIME_DESC,
            SortOption.fromHttp("group_info", "createTime", null));
        assertEquals(SortOption.GROUP_INFO_CREATE_TIME_DESC,
            SortOption.fromHttp("group_info", "createTime", ""));
    }

    @Test
    @DisplayName("列名为空时返回该表默认项（不报错）")
    void blankFieldFallsBackToTableDefault() {
        assertEquals(SortOption.GROUP_INFO_CREATE_TIME_DESC,
            SortOption.fromHttp("group_info", null, null));
        assertEquals(SortOption.USER_INFO_BEAUTY_ID_DESC,
            SortOption.fromHttp("user_info_beauty", "  ", "desc"));
    }

    @Test
    @DisplayName("多列排序项按整段字面量返回（role asc, create_time asc）")
    void multiColumnSortKeepsWholeFragment() {
        SortOption s = SortOption.fromHttp("user_contact", "roleThenCreateTime", "asc");
        assertEquals("role asc, create_time asc", s.getSql());
    }

    @Test
    @DisplayName("同一张表的三个排序列各自独立（不会互相串味）")
    void sameTableMultipleColumnsAreDistinct() {
        assertEquals("last_update_time desc",
            SortOption.fromHttp("user_contact", "lastUpdateTime", "desc").getSql());
        assertEquals("create_time asc",
            SortOption.fromHttp("user_contact", "createTime", "asc").getSql());
        assertEquals("role asc, create_time asc",
            SortOption.fromHttp("user_contact", "roleThenCreateTime", "asc").getSql());
    }

    // ── 2. 白名单外取值必须报错（ADR-004）─────────────────────────

    @Test
    @DisplayName("注入载荷作为列名 → CODE_1001，不静默回退")
    void injectionPayloadAsFieldIsRejected() {
        BusinessException e = assertThrows(BusinessException.class, () ->
            SortOption.fromHttp("group_info", "(select 1 from information_schema.tables)", "desc"));
        assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
    }

    @Test
    @DisplayName("列名里夹带空格与方向（'create_time desc'）→ CODE_1001")
    void fieldContainingSpaceAndDirectionIsRejected() {
        assertThrows(BusinessException.class, () ->
            SortOption.fromHttp("user_info", "create_time desc", "desc"));
    }

    @Test
    @DisplayName("方向不是 asc/desc（如 'desc; drop table x'）→ CODE_1001")
    void illegalDirectionIsRejected() {
        BusinessException e = assertThrows(BusinessException.class, () ->
            SortOption.fromHttp("group_info", "createTime", "desc; drop table x"));
        assertEquals(ResponseCodeEnum.CODE_1001.getCode(), e.getCode());
    }

    @Test
    @DisplayName("表名不在白名单内 → CODE_1001（表名由服务端写死，此处是防御）")
    void unknownTableIsRejected() {
        assertThrows(BusinessException.class, () ->
            SortOption.fromHttp("mysql.user", "createTime", "desc"));
        assertThrows(BusinessException.class, () -> SortOption.defaultOf("no_such_table"));
    }

    @Test
    @DisplayName("合法列名 + 非法方向 也必须报错（不能只校验列名）")
    void validColumnWithIllegalDirectionStillRejected() {
        assertThrows(BusinessException.class, () ->
            SortOption.fromHttp("group_info", "createTime", "desc, (select 1)"));
    }

    @Test
    @DisplayName("错误信息不得回显调用方原始输入（避免日志注入）")
    void errorMessageDoesNotEchoRawInput() {
        String payload = "<script>alert(1)</script>";
        BusinessException e = assertThrows(BusinessException.class, () ->
            SortOption.fromHttp("group_info", payload, "desc"));
        assertTrue(!e.getMessage().contains(payload),
            "错误信息回显了调用方原文: " + e.getMessage());
    }

    // ── 3. 默认项与不变量 ───────────────────────────────────────

    @Test
    @DisplayName("修复涉及的 16 张表全部有默认排序项")
    void everyTouchedTableHasDefault() {
        String[] tables = {
            "group_info", "user_info", "user_info_beauty", "app_update",
            "user_contact", "user_contact_apply", "chat_session_user", "group_file",
            "moment", "moment_like", "moment_comment", "moment_notify",
            "chat_message", "moment_media", "chat_session", "email_verify_code"
        };
        for (String t : tables) {
            SortOption d = SortOption.defaultOf(t);
            assertNotNull(d, "表 " + t + " 缺默认排序项");
            assertEquals(t, d.getTable(), "默认项的 table 字段与查询表不一致: " + t);
            assertTrue(d.getSql() != null && !d.getSql().isEmpty(), "默认项 SQL 片段为空: " + t);
        }
    }

    @Test
    @DisplayName("枚举项的 table 字段必须出现在其 SQL 片段所属的语义表内（防串表）")
    void enumTableMatchesSqlFragment() {
        for (SortOption s : SortOption.values()) {
            assertNotNull(s.getTable());
            assertTrue(s.getSql().toLowerCase().contains("order") == false,
                "SQL 片段不应含 order by 关键字（ORDER BY 由 XML 的 choose 分支写）: " + s.name());
            assertTrue(s.getSql().endsWith("asc") || s.getSql().endsWith("desc"),
                "SQL 片段必须以方向结尾: " + s.name() + " → " + s.getSql());
        }
    }

    @Test
    @DisplayName("不存在两个枚举项指向同一 (表, 列, 方向) —— 否则 XML 分支会二义")
    void noDuplicateTableFieldDirection() {
        Set<String> seen = new HashSet<>();
        for (SortOption s : SortOption.values()) {
            String key = s.getTable() + "|" + s.getHttpField() + "|" + s.getDirection();
            assertTrue(seen.add(key), "重复的 (表,列,方向): " + key);
        }
    }

    @Test
    @DisplayName("默认项必须属于本枚举（即 defaultOf 不返回 null 也不返回表外的项）")
    void defaultOfReturnsMember() {
        for (String t : new String[]{"group_info", "user_info_beauty", "chat_message"}) {
            SortOption d = SortOption.defaultOf(t);
            assertNotNull(d);
            assertSame(d, SortOption.valueOf(d.name()));
        }
    }
}
