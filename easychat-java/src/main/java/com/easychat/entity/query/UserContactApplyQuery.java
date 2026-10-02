package com.easychat.entity.query;


/**
 * 联系人申请参数
 */
public class UserContactApplyQuery extends BaseParam {


    /**
     * 自增ID
     */
    private Integer applyId;

    /**
     * 申请人id
     */
    private String applyUserId;

    private String applyUserIdFuzzy;

    /**
     * 接收人ID
     */
    private String receiveUserId;

    private String receiveUserIdFuzzy;

    /**
     * 当前操作者 id（仅用于「我可见的申请」判定，非持久化字段）
     * <p>
     * 语义：待处理申请对我可见，当且仅当满足其一：
     * <ol>
     *   <li>{@code receive_user_id = currentUserId}（我被直接申请）</li>
     *   <li>{@code contact_type = 1}（群入群申请）且我在该群 role ∈ {0 群主, 1 管理员} 且 status=1</li>
     * </ol>
     * 与 {@code receiveUserId} 的区别：后者是「申请单上的接收人字段」等值条件，
     * 会漏掉群管理员（申请单 receive_user_id 恒为群主）；本字段专供可见性判定。
     * <p>
     * 对应 XML：{@code UserContactApplyMapper.xml#query_condition} 的
     * {@code currentUserId} 分支。该条件同时作用于 {@code selectList} 与
     * {@code selectCount}，保证分页 total 与列表、WS 申请红点三处口径一致。
     *
     * @since 2026-10-02 群入群审批闭环（openspec/specs/group-join-approval）
     */
    private String currentUserId;

    /**
     * 联系人类型 0:好友 1:群组
     */
    private Integer contactType;

    public String getCurrentUserId() {
        return currentUserId;
    }

    public void setCurrentUserId(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    /**
     * 联系人群组ID
     */
    private String contactId;

    private String contactIdFuzzy;

    /**
     * 最后申请时间
     */
    private Long lastApplyTime;

    /**
     * 状态0:待处理 1:已同意  2:已拒绝 3:已拉黑
     */
    private Integer status;

    /**
     * 申请信息
     */
    private String applyInfo;

    private String applyInfoFuzzy;

    private Boolean queryContactInfo;

    private Long lastApplyTimestamp;

    public Long getLastApplyTimestamp() {
        return lastApplyTimestamp;
    }

    public void setLastApplyTimestamp(Long lastApplyTimestamp) {
        this.lastApplyTimestamp = lastApplyTimestamp;
    }

    public void setApplyId(Integer applyId) {
        this.applyId = applyId;
    }

    public Integer getApplyId() {
        return this.applyId;
    }

    public void setApplyUserId(String applyUserId) {
        this.applyUserId = applyUserId;
    }

    public String getApplyUserId() {
        return this.applyUserId;
    }

    public void setApplyUserIdFuzzy(String applyUserIdFuzzy) {
        this.applyUserIdFuzzy = applyUserIdFuzzy;
    }

    public String getApplyUserIdFuzzy() {
        return this.applyUserIdFuzzy;
    }

    public void setReceiveUserId(String receiveUserId) {
        this.receiveUserId = receiveUserId;
    }

    public String getReceiveUserId() {
        return this.receiveUserId;
    }

    public void setReceiveUserIdFuzzy(String receiveUserIdFuzzy) {
        this.receiveUserIdFuzzy = receiveUserIdFuzzy;
    }

    public String getReceiveUserIdFuzzy() {
        return this.receiveUserIdFuzzy;
    }

    public void setContactType(Integer contactType) {
        this.contactType = contactType;
    }

    public Integer getContactType() {
        return this.contactType;
    }

    public void setContactId(String contactId) {
        this.contactId = contactId;
    }

    public String getContactId() {
        return this.contactId;
    }

    public void setContactIdFuzzy(String contactIdFuzzy) {
        this.contactIdFuzzy = contactIdFuzzy;
    }

    public String getContactIdFuzzy() {
        return this.contactIdFuzzy;
    }

    public Long getLastApplyTime() {
        return lastApplyTime;
    }

    public void setLastApplyTime(Long lastApplyTime) {
        this.lastApplyTime = lastApplyTime;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public void setApplyInfo(String applyInfo) {
        this.applyInfo = applyInfo;
    }

    public String getApplyInfo() {
        return this.applyInfo;
    }

    public void setApplyInfoFuzzy(String applyInfoFuzzy) {
        this.applyInfoFuzzy = applyInfoFuzzy;
    }

    public String getApplyInfoFuzzy() {
        return this.applyInfoFuzzy;
    }

    public Boolean getQueryContactInfo() {
        return queryContactInfo;
    }

    public void setQueryContactInfo(Boolean queryContactInfo) {
        this.queryContactInfo = queryContactInfo;
    }
}
