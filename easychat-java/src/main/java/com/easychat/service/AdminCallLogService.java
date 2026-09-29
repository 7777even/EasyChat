package com.easychat.service;

import com.easychat.entity.query.CallLogQuery;
import com.easychat.entity.vo.AdminCallLogVO;
import com.easychat.entity.vo.PaginationResultVO;

/**
 * 通话记录管理端 业务接口
 */
public interface AdminCallLogService {

    /**
     * 管理端通话记录分页列表（类型/媒体/状态/发起人/时间范围筛选 + 昵称群名解析）
     */
    PaginationResultVO<AdminCallLogVO> loadCallLog(CallLogQuery query);
}
