package com.easychat.entity.query;


/**
 * 用户信息参数
 */
public class UserInfoQuery extends BaseParam {


    /**
     * 用户ID
     */
    private String userId;

    private String userIdFuzzy;

    /**
     * 邮箱
     */
    private String email;

    private String emailFuzzy;

    /**
     * 昵称
     */
    private String nickName;

    private String nickNameFuzzy;

    /**
     * 0:直接加入 1:同意后加好友
     */
    private Integer joinType;

    /**
     * 0:女 1:男
     */
    private Integer sex;

    // 2026-10-06 移除 `password` 与 `passwordFuzzy` 两个字段（遗留 #24）。
    //   /admin/loadUser 直接绑定本对象，这两个字段使调用方可把 password 列塞进 WHERE；
    //   存量 MD5 账号哈希无盐且确定，`password like '%<MD5(猜测)>%'` 即明文口令猜测预言机。
    //   对应的两个 <if> 也已从 UserInfoMapper.xml 删除。查密码不是管理端该有的能力。

    /**
     * 个性签名
     */
    private String personalSignature;

    private String personalSignatureFuzzy;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 创建时间
     */
    private String createTime;

    private String createTimeStart;

    private String createTimeEnd;

    /**
     * 最后登录时间
     */
    private Long lastLoginTime;

    /**
     * 省份
     */
    private String areaName;

    private String areaNameFuzzy;

    /**
     * 城市
     */
    private String areaCode;

    private String areaCodeFuzzy;

    /**
     * 最后离开时间
     */
    private Long lastOffTime;


    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return this.userId;
    }

    public void setUserIdFuzzy(String userIdFuzzy) {
        this.userIdFuzzy = userIdFuzzy;
    }

    public String getUserIdFuzzy() {
        return this.userIdFuzzy;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmail() {
        return this.email;
    }

    public void setEmailFuzzy(String emailFuzzy) {
        this.emailFuzzy = emailFuzzy;
    }

    public String getEmailFuzzy() {
        return this.emailFuzzy;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getNickName() {
        return this.nickName;
    }

    public void setNickNameFuzzy(String nickNameFuzzy) {
        this.nickNameFuzzy = nickNameFuzzy;
    }

    public String getNickNameFuzzy() {
        return this.nickNameFuzzy;
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

    public void setPersonalSignature(String personalSignature) {
        this.personalSignature = personalSignature;
    }

    public String getPersonalSignature() {
        return this.personalSignature;
    }

    public void setPersonalSignatureFuzzy(String personalSignatureFuzzy) {
        this.personalSignatureFuzzy = personalSignatureFuzzy;
    }

    public String getPersonalSignatureFuzzy() {
        return this.personalSignatureFuzzy;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getCreateTime() {
        return this.createTime;
    }

    public void setCreateTimeStart(String createTimeStart) {
        this.createTimeStart = createTimeStart;
    }

    public String getCreateTimeStart() {
        return this.createTimeStart;
    }

    public void setCreateTimeEnd(String createTimeEnd) {
        this.createTimeEnd = createTimeEnd;
    }

    public String getCreateTimeEnd() {
        return this.createTimeEnd;
    }

    public Long getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(Long lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public String getAreaName() {
        return this.areaName;
    }

    public void setAreaNameFuzzy(String areaNameFuzzy) {
        this.areaNameFuzzy = areaNameFuzzy;
    }

    public String getAreaNameFuzzy() {
        return this.areaNameFuzzy;
    }

    public void setAreaCode(String areaCode) {
        this.areaCode = areaCode;
    }

    public String getAreaCode() {
        return this.areaCode;
    }

    public void setAreaCodeFuzzy(String areaCodeFuzzy) {
        this.areaCodeFuzzy = areaCodeFuzzy;
    }

    public String getAreaCodeFuzzy() {
        return this.areaCodeFuzzy;
    }

    public void setLastOffTime(Long lastOffTime) {
        this.lastOffTime = lastOffTime;
    }

    public Long getLastOffTime() {
        return this.lastOffTime;
    }

}
