package com.easychat.entity.po;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.enums.DateTimePatternEnum;
import com.easychat.utils.DateUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.Date;


/**
 * 用户信息
 */
public class UserInfo implements Serializable {


    /**
     * 用户ID
     */
    @NotEmpty
    private String userId;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 昵称
     */
    private String nickName;

    /**
     * 0:直接加入 1:同意后加好友
     */
    private Integer joinType;

    /**
     * 0:女 1:男
     */
    private Integer sex;

    /**
     * 密码
     */
    @JsonIgnore
    private String password;

    /**
     * 个性签名
     */
    private String personalSignature;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /**
     * 最后登录时间
     */
    private Date lastLoginTime;

    /**
     * 省份
     */
    private String areaName;

    /**
     * 城市
     */
    private String areaCode;

    /**
     * 最后离开时间
     */
    private Long lastOffTime;

    /**
     * 朋友圈默认可见范围（用户级）
     * <p>
     * 语义与既有 {@code moment.visibility} 完全对齐：
     * 0 公开 / 1 仅好友 / 2 仅自己 / 3 自定义白名单 / 4 黑名单。
     * 仅作为**发布时的默认值**参与，不参与 {@code canView} 判定。
     * <p>
     * <b>刻意不设 Java 字段初始值</b>：默认值由 DDL 的 {@code NOT NULL DEFAULT 0} 与读取路径承担。
     * 若在此处写 {@code = 0}，则 {@code new UserInfo()} 天然带 0，
     * 而 {@code UserInfoMapper.xml#updateByUserId} 的 {@code <if test="bean.xxx != null">}
     * 会把它一并写进 SQL —— 导致「改在线状态开关时顺带把朋友圈可见范围重置为 0」这类串列 bug。
     *
     * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
     */
    private Integer momentVisibility;

    /** 朋友圈自定义白名单，JSON 数组字符串（momentVisibility=3 生效） */
    private String momentVisibleList;

    /** 朋友圈自定义黑名单，JSON 数组字符串（momentVisibility=4 生效） */
    private String momentInvisibleList;

    /**
     * 是否对好友展示在线状态：1 展示（默认）/ 0 隐藏
     * <p>
     * 同上，<b>不设 Java 字段初始值</b>，默认值由 DDL {@code NOT NULL DEFAULT 1} 承担。
     *
     * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
     */
    private Integer onlineStatusVisible;

    private Integer onlineType;

    public Integer getOnlineType() {
        if (lastLoginTime != null && lastLoginTime.getTime() > lastOffTime) {
            return Constants.ONE;
        } else {
            return Constants.ZERO;
        }
    }

    public void setOnlineType(Integer onlineType) {
        this.onlineType = onlineType;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return this.userId;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmail() {
        return this.email;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getNickName() {
        return this.nickName;
    }

    public void setJoinType(Integer joinType) {
        this.joinType = joinType;
    }

    public Integer getJoinType() {
        return this.joinType;
    }

    public void setSex(Integer sex) {
        this.sex = sex;
    }

    public Integer getSex() {
        return this.sex;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPersonalSignature(String personalSignature) {
        this.personalSignature = personalSignature;
    }

    public String getPersonalSignature() {
        return this.personalSignature;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getCreateTime() {
        return this.createTime;
    }

    public Date getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(Date lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public String getAreaName() {
        return this.areaName;
    }

    public void setAreaCode(String areaCode) {
        this.areaCode = areaCode;
    }

    public String getAreaCode() {
        return this.areaCode;
    }

    public void setLastOffTime(Long lastOffTime) {
        this.lastOffTime = lastOffTime;
    }

    public Long getLastOffTime() {
        return this.lastOffTime;
    }

    public Integer getMomentVisibility() {
        return momentVisibility;
    }

    public void setMomentVisibility(Integer momentVisibility) {
        this.momentVisibility = momentVisibility;
    }

    public String getMomentVisibleList() {
        return momentVisibleList;
    }

    public void setMomentVisibleList(String momentVisibleList) {
        this.momentVisibleList = momentVisibleList;
    }

    public String getMomentInvisibleList() {
        return momentInvisibleList;
    }

    public void setMomentInvisibleList(String momentInvisibleList) {
        this.momentInvisibleList = momentInvisibleList;
    }

    public Integer getOnlineStatusVisible() {
        return onlineStatusVisible;
    }

    public void setOnlineStatusVisible(Integer onlineStatusVisible) {
        this.onlineStatusVisible = onlineStatusVisible;
    }

    @Override
    public String toString() {
        return "用户ID:" + (userId == null ? "空" : userId) + "，邮箱:" + (email == null ? "空" : email) + "，昵称:" + (nickName == null ? "空" : nickName) + "，0:直接加入 1:同意后加好友:" + (joinType == null ? "空" : joinType) + "，0:女 1:男:" + (sex == null ? "空" : sex) + "，密码:" + (password == null || password.isEmpty() ? "空" : "***已设置(" + password.length() + "字符)***") + "，个性签名:" + (personalSignature == null ? "空" : personalSignature) + "，状态:" + (status == null ? "空" : status) + "，创建时间:" + (createTime == null ? "空" : DateUtil.format(createTime, DateTimePatternEnum.YYYY_MM_DD_HH_MM_SS.getPattern())) + "，最后登录时间:" + (lastLoginTime == null ? "空" : lastLoginTime) + "，省份:" + (areaName == null ? "空" : areaName) + "，城市:" + (areaCode == null ? "空" : areaCode) + "，最后离开时间:" + (lastOffTime == null ? "空" : lastOffTime);
    }
}
