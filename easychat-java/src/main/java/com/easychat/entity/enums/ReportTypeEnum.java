package com.easychat.entity.enums;

import java.util.Arrays;

/**
 * 举报对象类型（统一视图判别）
 */
public enum ReportTypeEnum {
    MOMENT(1, "朋友圈动态"),
    COMMENT(2, "朋友圈评论"),
    MESSAGE(3, "聊天消息");

    private final Integer code;
    private final String desc;

    ReportTypeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ReportTypeEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
    }
}
