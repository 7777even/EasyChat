package com.easychat.utils;

import com.easychat.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 序列化/反序列化工具类测试（2026-10-04）
 *
 * <p>⚠️ <b>断言口径</b>：锁定现状。其中标注「⚠ 现状」的用例断言的是实际行为，
 * 对应缺陷已登记于 {@code docs/system-facts.md} §14。
 */
@DisplayName("JsonUtils — JSON 序列化与反序列化")
class JsonUtilsTest {

    /** 供序列化测试用的简单 POJO。 */
    public static class Sample {
        private String name;
        private Integer age;
        private String nullableField;

        public String getName() { return name; }

        public void setName(String name) { this.name = name; }

        public Integer getAge() { return age; }

        public void setAge(Integer age) { this.age = age; }

        public String getNullableField() { return nullableField; }

        public void setNullableField(String nullableField) { this.nullableField = nullableField; }
    }

    // ─────────────────────────────────────────────────────────────
    // convertObj2Json
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("convertObj2Json: 正常序列化")
    void serializeObject() {
        Sample s = new Sample();
        s.setName("张三");
        s.setAge(20);
        String json = JsonUtils.convertObj2Json(s);
        assertNotNull(json);
        assertTrue(json.contains("\"name\":\"张三\""), "实际：" + json);
        assertTrue(json.contains("\"age\":20"), "实际：" + json);
    }

    @Test
    @DisplayName("convertObj2Json: WriteMapNullValue 使 null 字段被保留")
    void serializeKeepsNullFields() {
        // FEATURES 含 SerializerFeature.WriteMapNullValue，故 null 字段不会被省略。
        // 这条口径影响下游：前端拿到的 VO 字段恒定存在，不会因某字段为 null 而「消失」。
        Sample s = new Sample();
        s.setName("李四");
        s.setAge(null);
        s.setNullableField(null);
        String json = JsonUtils.convertObj2Json(s);
        assertTrue(json.contains("\"age\":null"), "null 字段应被保留，实际：" + json);
        assertTrue(json.contains("\"nullableField\":null"), "实际：" + json);
    }

    @Test
    @DisplayName("convertObj2Json: null 对象")
    void serializeNull() {
        assertEquals("null", JsonUtils.convertObj2Json(null));
    }

    // ─────────────────────────────────────────────────────────────
    // convertJson2Obj
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("convertJson2Obj: 正常反序列化")
    void deserializeObject() {
        Sample s = JsonUtils.convertJson2Obj("{\"name\":\"王五\",\"age\":30}", Sample.class);
        assertNotNull(s);
        assertEquals("王五", s.getName());
        assertEquals(30, s.getAge());
    }

    @Test
    @DisplayName("convertJson2Obj: ⚠ 现状 null / 空串输入返回 null 而非抛异常")
    void deserializeNullReturnsNull() {
        // ⚠ 现状（已登记为缺陷候选）：入参为 null 时 fastjson 直接返回 null，
        //   不进入 catch，因此**不抛 CODE_2102**。调用方拿到 null 后
        //   若直接解引用会得到 NPE，而非可诊断的业务异常。
        assertNull(JsonUtils.convertJson2Obj(null, Sample.class));
        assertNull(JsonUtils.convertJson2Obj("", Sample.class));
    }

    @Test
    @DisplayName("convertJson2Obj: 非法 JSON 抛 CODE_2102")
    void deserializeMalformedThrows() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> JsonUtils.convertJson2Obj("not-json", Sample.class));
        assertEquals(2102, e.getCode());
        assertThrows(BusinessException.class,
                () -> JsonUtils.convertJson2Obj("{\"name\":", Sample.class));
        // 类型不匹配也应被归一到同一错误码
        assertThrows(BusinessException.class,
                () -> JsonUtils.convertJson2Obj("{\"age\":\"非数字\"}", Sample.class));
    }

    // ─────────────────────────────────────────────────────────────
    // convertJsonArray2List
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("convertJsonArray2List: 正常反序列化数组")
    void deserializeArray() {
        List<Sample> list = JsonUtils.convertJsonArray2List(
                "[{\"name\":\"甲\",\"age\":1},{\"name\":\"乙\",\"age\":2}]", Sample.class);
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("甲", list.get(0).getName());
        assertEquals("乙", list.get(1).getName());
        assertEquals(2, list.get(1).getAge());
    }

    @Test
    @DisplayName("convertJsonArray2List: 空数组返回空列表")
    void deserializeEmptyArray() {
        assertTrue(JsonUtils.convertJsonArray2List("[]", Sample.class).isEmpty());
    }

    @Test
    @DisplayName("convertJsonArray2List: 非法 JSON 抛 CODE_2102")
    void deserializeMalformedArrayThrows() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> JsonUtils.convertJsonArray2List("not-json", Sample.class));
        assertEquals(2102, e.getCode());
        assertThrows(BusinessException.class,
                () -> JsonUtils.convertJsonArray2List("[{\"name\":", Sample.class));
    }

    @Test
    @DisplayName("convertJsonArray2List: ⚠ 现状 null 输入返回 null 而非抛异常")
    void deserializeNullArrayReturnsNull() {
        // ⚠ 同 deserializeNullReturnsNull：fastjson 对 null 返回 null，不进 catch。
        assertNull(JsonUtils.convertJsonArray2List(null, Sample.class));
    }

    @Test
    @DisplayName("convertObj2Json 与 convertJson2Obj 往返一致")
    void roundTrip() {
        Sample s = new Sample();
        s.setName("往返");
        s.setAge(7);
        s.setNullableField(null);
        Sample back = JsonUtils.convertJson2Obj(JsonUtils.convertObj2Json(s), Sample.class);
        assertEquals("往返", back.getName());
        assertEquals(7, back.getAge());
        assertNull(back.getNullableField());
    }
}
