package com.easychat.entity.enums;

import java.util.Arrays;

/**
 * 举报处置动作
 */
public enum HandleActionEnum {
    NONE(0, "仅记录处理"),
    DELETE_CONTENT(1, "删除被举报内容"),
    BAN_PUBLISHER(2, "封禁发布者");

    private final Integer code;
    private final String desc;

    HandleActionEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static HandleActionEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
    }
}
