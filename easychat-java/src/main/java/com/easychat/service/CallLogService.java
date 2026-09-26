package com.easychat.service;

import com.easychat.entity.po.CallLog;
import com.easychat.entity.query.CallLogQuery;

import java.util.List;

/**
 * 通话记录 业务接口
 */
public interface CallLogService {

    /**
     * 保存通话记录
     */
    Integer save(CallLog callLog);

    /**
     * 条件查询（回溯用，管理端列表可后续独立变更接入）
     */
    List<CallLog> findList(CallLogQuery query);
}
