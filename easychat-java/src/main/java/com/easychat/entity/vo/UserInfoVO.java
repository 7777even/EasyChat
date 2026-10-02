package com.easychat.entity.vo;

import java.io.Serializable;


/**
 *
 */
public class UserInfoVO implements Serializable {


    /**
     * 用户ID
     */
    private String userId;

    /**
     * 昵称
     */
    private String nickName;

    /**
     * 0:女 1:男
     */
    private Integer sex;

    private Integer joinType;

    /**
     * 朋友圈默认可见范围：0 公开 / 1 仅好友 / 2 仅自己 / 3 自定义白名单 / 4 黑名单
     * <p>
     * 与 {@code UserInfo#momentVisibility} 同名同类型，
     * 靠 {@code CopyTools.copy}（Spring {@code BeanUtils.copyProperties}）自动带出。
     * 隐私设置页与发布页据此回填。
     *
     * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
     */
    private Integer momentVisibility;

    /** 朋友圈自定义白名单，JSON 数组字符串（visibility=3 生效） */
    private String momentVisibleList;

    /** 朋友圈自定义黑名单，JSON 数组字符串（visibility=4 生效） */
    private String momentInvisibleList;

    /** 是否对好友展示在线状态：1 展示 / 0 隐藏 */
    private Integer onlineStatusVisible;

    /**
     * 个性签名
     */
    private String personalSignature;

    private String areaCode;

    private String areaName;

    private String token;

    private Boolean admin;

    private Integer contactStatus;

    /**
     * 好友备注名（仅当查看对象是自己的好友时返回，取自 user_contact.remark）
     */
    private String remark;

    /**
     * 好友分组名（仅当查看对象是自己的好友时返回，取自 user_contact.group_name）
     */
    private String groupName;

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public void setContactStatus(Integer contactStatus) {
        this.contactStatus = contactStatus;
    }

    public Integer getContactStatus() {
        return contactStatus;
    }

    public Boolean getAdmin() {
        return admin;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public Integer getSex() {
        return sex;
    }

    public void setSex(Integer sex) {
        this.sex = sex;
    }

    public String getPersonalSignature() {
        return personalSignature;
    }

    public void setPersonalSignature(String personalSignature) {
        this.personalSignature = personalSignature;
    }

    public String getAreaCode() {
        return areaCode;
    }

    public void setAreaCode(String areaCode) {
        this.areaCode = areaCode;
    }

    public String getAreaName() {
        return areaName;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public Integer getJoinType() {
        return joinType;
    }

    public void setJoinType(Integer joinType) {
        this.joinType = joinType;
    }

    /**
     * 朋友圈默认可见范围：0 公开 / 1 仅好友 / 2 仅自己 / 3 自定义白名单 / 4 黑名单
     * <p>
     * 与 {@code UserInfo#momentVisibility} 同名同类型，
     * 靠 {@code CopyTools.copy}（Spring {@code BeanUtils.copyProperties}）自动带出。
     *
     * @since 2026-10-02 隐私设置
     */
    public Integer getMomentVisibility() {
        return momentVisibility;
    }

    public void setMomentVisibility(Integer momentVisibility) {
        this.momentVisibility = momentVisibility;
    }

    /** 朋友圈自定义白名单，JSON 数组字符串 */
    public String getMomentVisibleList() {
        return momentVisibleList;
    }

    public void setMomentVisibleList(String momentVisibleList) {
        this.momentVisibleList = momentVisibleList;
    }

    /** 朋友圈自定义黑名单，JSON 数组字符串 */
    public String getMomentInvisibleList() {
        return momentInvisibleList;
    }

    public void setMomentInvisibleList(String momentInvisibleList) {
        this.momentInvisibleList = momentInvisibleList;
    }

    /** 是否对好友展示在线状态：1 展示 / 0 隐藏 */
    public Integer getOnlineStatusVisible() {
        return onlineStatusVisible;
    }

    public void setOnlineStatusVisible(Integer onlineStatusVisible) {
        this.onlineStatusVisible = onlineStatusVisible;
    }
}
