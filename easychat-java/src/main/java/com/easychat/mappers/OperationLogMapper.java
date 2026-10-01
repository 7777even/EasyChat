package com.easychat.mappers;

import com.easychat.entity.po.OperationLog;
import com.easychat.entity.query.OperationLogQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 操作日志 Mapper
 */
public interface OperationLogMapper<OperationLog, OperationLogQuery> {

    /**
     * 插入
     */
    int insert(OperationLog operationLog);

    /**
     * 根据条件查询列表
     */
    List<OperationLog> selectList(OperationLogQuery query);

    /**
     * 根据条件查询数量
     */
    Integer selectCount(OperationLogQuery query);
}
