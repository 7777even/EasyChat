package com.easychat.entity.enums;

/**
 * 群成员角色枚举
 */
public enum GroupMemberRoleEnum {
    OWNER(0, "群主"),
    ADMIN(1, "管理员"),
    MEMBER(2, "成员");

    private Integer role;
    private String desc;

    GroupMemberRoleEnum(Integer role, String desc) {
        this.role = role;
        this.desc = desc;
    }

    public static GroupMemberRoleEnum getByRole(Integer role) {
        for (GroupMemberRoleEnum item : GroupMemberRoleEnum.values()) {
            if (item.getRole().equals(role)) {
                return item;
            }
        }
        return null;
    }

    public Integer getRole() {
        return role;
    }

    public String getDesc() {
        return desc;
    }
}
