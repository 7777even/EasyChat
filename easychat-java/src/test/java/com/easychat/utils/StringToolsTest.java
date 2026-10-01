package com.easychat.utils;

import com.easychat.exception.BusinessException;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * StringTools 单元测试
 */
public class StringToolsTest {

    // ======================== isEmpty ========================

    @Test
    public void isEmpty_null() {
        assertTrue(StringTools.isEmpty(null));
    }

    @Test
    public void isEmpty_empty() {
        assertTrue(StringTools.isEmpty(""));
    }

    @Test
    public void isEmpty_nullString() {
        assertTrue(StringTools.isEmpty("null"));
    }

    @Test
    public void isEmpty_emptyChar() {
        assertTrue(StringTools.isEmpty("\u0000"));
    }

    @Test
    public void isEmpty_whitespace() {
        assertTrue(StringTools.isEmpty("   "));
    }

    @Test
    public void isEmpty_notEmpty() {
        assertFalse(StringTools.isEmpty("hello"));
    }

    // ======================== isNumber ========================

    @Test
    public void isNumber_null() {
        assertFalse(StringTools.isNumber(null));
    }

    @Test
    public void isNumber_valid() {
        assertTrue(StringTools.isNumber("123456"));
    }

    @Test
    public void isNumber_invalid() {
        assertFalse(StringTools.isNumber("abc"));
    }

    @Test
    public void isNumber_mixed() {
        assertFalse(StringTools.isNumber("123abc"));
    }

    // ======================== encodeByMD5 ========================

    @Test
    public void encodeByMD5_null() {
        assertNull(StringTools.encodeByMD5(null));
    }

    @Test
    public void encodeByMD5_empty() {
        assertNull(StringTools.encodeByMD5(""));
    }

    @Test
    public void encodeByMD5_valid() {
        String result = StringTools.encodeByMD5("password123");
        assertNotNull(result);
        assertEquals(32, result.length());
        // MD5("password123") = 482c811da5d5b4bc6d497ffa98491e38
        assertEquals("482c811da5d5b4bc6d497ffa98491e38", result);
    }

    // ======================== getRandomNumber ========================

    @Test
    public void getRandomNumber_length() {
        String result = StringTools.getRandomNumber(6);
        assertNotNull(result);
        assertEquals(6, result.length());
    }

    @Test
    public void getRandomNumber_isNumeric() {
        String result = StringTools.getRandomNumber(10);
        assertTrue(result.matches("^[0-9]+$"));
    }

    // ======================== getRandomString ========================

    @Test
    public void getRandomString_length() {
        String result = StringTools.getRandomString(20);
        assertNotNull(result);
        assertEquals(20, result.length());
    }

    // ======================== getChatSessionId4User ========================

    @Test
    public void getChatSessionId4User_success() {
        String[] userIds = {"U12345678901", "U99999999999"};
        String result = StringTools.getChatSessionId4User(userIds);
        assertNotNull(result);
        assertEquals(32, result.length());
    }

    @Test
    public void getChatSessionId4User_sorted() {
        // 测试排序：不同顺序的输入应该产生相同的结果
        String[] userIds1 = {"U12345678901", "U99999999999"};
        String[] userIds2 = {"U99999999999", "U12345678901"};

        String result1 = StringTools.getChatSessionId4User(userIds1);
        String result2 = StringTools.getChatSessionId4User(userIds2);

        assertEquals(result1, result2);
    }

    // ======================== getChatSessionId4Group ========================

    @Test
    public void getChatSessionId4Group_success() {
        String result = StringTools.getChatSessionId4Group("G99999999999");
        assertNotNull(result);
        assertEquals(32, result.length());
    }

    // ======================== getGroupId / getUserId ========================

    @Test
    public void getGroupId_success() {
        String result = StringTools.getGroupId();
        assertNotNull(result);
        assertTrue(result.startsWith("G"));
        assertEquals(12, result.length());
    }

    @Test
    public void getUserId_success() {
        String result = StringTools.getUserId();
        assertNotNull(result);
        assertTrue(result.startsWith("U"));
        assertEquals(12, result.length());
    }

    // ======================== getFileSuffix ========================

    @Test
    public void getFileSuffix_success() {
        String result = StringTools.getFileSuffix("test.jpg");
        assertEquals(".jpg", result);
    }

    @Test
    public void getFileSuffix_multipleDots() {
        String result = StringTools.getFileSuffix("archive.tar.gz");
        assertEquals(".gz", result);
    }

    // ======================== pathIsOk ========================

    @Test
    public void pathIsOk_empty() {
        assertTrue(StringTools.pathIsOk(""));
    }

    @Test
    public void pathIsOk_null() {
        assertTrue(StringTools.pathIsOk(null));
    }

    @Test
    public void pathIsOk_normal() {
        assertTrue(StringTools.pathIsOk("/images/test.jpg"));
    }

    @Test
    public void pathIsOk_parentDir() {
        assertFalse(StringTools.pathIsOk("../secret.txt"));
    }

    @Test
    public void pathIsOk_parentDirWindows() {
        assertFalse(StringTools.pathIsOk("..\\secret.txt"));
    }

    // ======================== cleanHtmlTag ========================

    @Test
    public void cleanHtmlTag_null() {
        assertNull(StringTools.cleanHtmlTag(null));
    }

    @Test
    public void cleanHtmlTag_empty() {
        assertEquals("", StringTools.cleanHtmlTag(""));
    }

    @Test
    public void cleanHtmlTag_replaceLt() {
        String result = StringTools.cleanHtmlTag("<script>alert('xss')</script>");
        assertEquals("&lt;script>alert('xss')&lt;/script>", result);
    }

    @Test
    public void cleanHtmlTag_replaceNewline() {
        String result = StringTools.cleanHtmlTag("line1\nline2");
        assertEquals("line1<br>line2", result);
    }

    @Test
    public void cleanHtmlTag_replaceCrLf() {
        String result = StringTools.cleanHtmlTag("line1\r\nline2");
        assertEquals("line1<br>line2", result);
    }

    // ======================== resetMessageContent ========================

    @Test
    public void resetMessageContent_success() {
        String result = StringTools.resetMessageContent("<b>bold</b>");
        assertEquals("&lt;b>bold&lt;/b>", result);
    }

    // ======================== stringToLongList ========================

    @Test
    public void stringToLongList_empty() {
        assertTrue(StringTools.stringToLongList("").isEmpty());
    }

    @Test
    public void stringToLongList_null() {
        assertTrue(StringTools.stringToLongList(null).isEmpty());
    }

    @Test
    public void stringToLongList_valid() {
        java.util.List<Long> result = StringTools.stringToLongList("1,2,3,4,5");
        assertEquals(5, result.size());
        assertEquals(Long.valueOf(1L), result.get(0));
        assertEquals(Long.valueOf(5L), result.get(4));
    }

    @Test
    public void stringToLongList_withSpaces() {
        java.util.List<Long> result = StringTools.stringToLongList("1, 2, 3");
        assertEquals(3, result.size());
    }

    @Test
    public void stringToLongList_invalidValues() {
        java.util.List<Long> result = StringTools.stringToLongList("1,abc,3");
        assertEquals(2, result.size());
        assertEquals(Long.valueOf(1L), result.get(0));
        assertEquals(Long.valueOf(3L), result.get(1));
    }

    // ======================== checkParam ========================

    @Test
    public void checkParam_valid() {
        // 不应该抛出异常
        StringTools.checkParam(new TestParam("value"));
    }

    @Test(expected = BusinessException.class)
    public void checkParam_allNull() {
        StringTools.checkParam(new TestParam(null));
    }

    @Test(expected = BusinessException.class)
    public void checkParam_allEmpty() {
        StringTools.checkParam(new TestParam(""));
    }

    // 测试用的内部类
    private static class TestParam {
        private String field;

        public TestParam(String field) {
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }
}
