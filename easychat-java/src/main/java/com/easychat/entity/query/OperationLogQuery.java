package com.easychat.entity.query;

/**
 * 操作日志查询条件
 */
public class OperationLogQuery extends BaseParam {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 操作类型
     */
    private String operationType;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }
}
