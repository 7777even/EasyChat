package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

import com.easychat.entity.po.CallLog;
import com.easychat.entity.query.CallLogQuery;

/**
 * 通话记录数据库操作接口
 */
public interface CallLogMapper extends BaseMapper<CallLog, CallLogQuery> {

    /**
     * 根据ID获取对象
     */
    CallLog selectById(@Param("id") Long id);
}
