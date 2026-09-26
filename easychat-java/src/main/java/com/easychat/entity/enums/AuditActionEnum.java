package com.easychat.entity.enums;

import java.util.Arrays;

/**
 * 审计动作（处置结论）
 */
public enum AuditActionEnum {
    HANDLE(1, "已处理"),
    REJECT(2, "已驳回");

    private final Integer code;
    private final String desc;

    AuditActionEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static AuditActionEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(e -> e.code.equals(code)).findFirst().orElse(null);
    }
}
