package com.easychat.entity.enums;

public enum CallTypeEnum {
    AUDIO(0, "语音通话"),
    VIDEO(1, "视频通话");

    private Integer type;
    private String desc;

    CallTypeEnum(Integer type, String desc) {
        this.type = type;
        this.desc = desc;
    }

    public Integer getType() {
        return type;
    }

    public String getDesc() {
        return desc;
    }

    public static CallTypeEnum getByType(Integer type) {
        for (CallTypeEnum item : CallTypeEnum.values()) {
            if (item.getType().equals(type)) {
                return item;
            }
        }
        return null;
    }
}
