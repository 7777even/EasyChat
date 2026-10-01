package com.easychat.entity.enums;

/**
 * 用户在线状态枚举
 */
public enum OnlineStatusEnum {
    ONLINE(1, "在线"),
    BUSY(2, "忙碌"),
    OFFLINE(3, "离线");

    private Integer status;
    private String desc;

    OnlineStatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }

    public static OnlineStatusEnum getByStatus(Integer status) {
        for (OnlineStatusEnum item : OnlineStatusEnum.values()) {
            if (item.getStatus().equals(status)) {
                return item;
            }
        }
        return null;
    }

    public Integer getStatus() {
        return status;
    }

    public String getDesc() {
        return desc;
    }
}
