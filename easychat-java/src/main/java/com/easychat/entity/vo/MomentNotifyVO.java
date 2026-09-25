package com.easychat.entity.vo;

/**
 * 朋友圈通知展示对象（含触发人昵称与内容摘要，供通知中心直接渲染）
 */
public class MomentNotifyVO {

    /** 通知ID */
    private Long id;

    /** 通知类型 0新动态 1点赞 2评论 3回复 4@ */
    private Integer type;

    /** 关联ID：动态ID 或 评论ID */
    private Long refId;

    /** 触发人用户ID */
    private String fromUserId;

    /** 触发人昵称 */
    private String fromNickName;

    /** 触发人头像文件ID（同 userId） */
    private String fromAvatar;

    /** 通知摘要内容 */
    private String content;

    /** 0未读 1已读 */
    private Integer readStatus;

    /** 通知时间 */
    private Long createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    public String getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(String fromUserId) {
        this.fromUserId = fromUserId;
    }

    public String getFromNickName() {
        return fromNickName;
    }

    public void setFromNickName(String fromNickName) {
        this.fromNickName = fromNickName;
    }

    public String getFromAvatar() {
        return fromAvatar;
    }

    public void setFromAvatar(String fromAvatar) {
        this.fromAvatar = fromAvatar;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(Integer readStatus) {
        this.readStatus = readStatus;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
