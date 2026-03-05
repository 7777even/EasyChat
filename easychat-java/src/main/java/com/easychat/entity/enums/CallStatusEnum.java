package com.easychat.entity.enums;

public enum CallStatusEnum {
    CALLING(0, "呼叫中"),
    ACCEPTED(1, "已接听"),
    REJECTED(2, "已拒绝"),
    CANCELLED(3, "已取消"),
    TIMEOUT(4, "超时未接听"),
    ENDED(5, "已结束");

    private Integer status;
    private String desc;

    CallStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }

    public static CallStatusEnum getByStatus(Integer status) {
        for (CallStatusEnum item : CallStatusEnum.values()) {
            if (item.getStatus().equals(status)) {
                return item;
            }
        }
        return null;
    }
}
