package com.easychat.entity.query;


/**
 * 朋友圈通知 查询参数
 */
public class MomentNotifyQuery extends BaseParam {

    /** ID */
    private Long id;

    /** 收件人用户ID */
    private String userId;

    /** 通知类型 0新动态 1点赞 2评论 3回复 4@ */
    private Integer type;

    /** 类型列表（多选筛选） */
    private Integer[] typeList;

    /** 关联ID（动态或评论） */
    private Long refId;

    /** 触发人 */
    private String fromUserId;

    /** 已读状态 0未读 1已读 */
    private Integer readStatus;

    /** 排序 */
    private String orderBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer[] getTypeList() {
        return typeList;
    }

    public void setTypeList(Integer[] typeList) {
        this.typeList = typeList;
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

    public Integer getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(Integer readStatus) {
        this.readStatus = readStatus;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = orderBy;
    }
}
