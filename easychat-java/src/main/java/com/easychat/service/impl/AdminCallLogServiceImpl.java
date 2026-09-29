package com.easychat.service.impl;

import com.easychat.entity.enums.PageSize;
import com.easychat.entity.query.CallLogQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.vo.AdminCallLogVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.mappers.CallLogReadMapper;
import com.easychat.service.AdminCallLogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service("adminCallLogService")
public class AdminCallLogServiceImpl implements AdminCallLogService {

    @Resource
    private CallLogReadMapper callLogReadMapper;

    @Override
    public PaginationResultVO<AdminCallLogVO> loadCallLog(CallLogQuery query) {
        // 安全：BaseParam.orderBy 可被 HTTP 绑定，服务端硬编码覆盖，请求传入值一律丢弃
        query.setOrderBy("cl.id desc");
        int count = callLogReadMapper.selectCallLogCount(query);
        int pageSize = query.getPageSize() == null ? PageSize.SIZE15.getSize() : query.getPageSize();
        SimplePage page = new SimplePage(query.getPageNo(), count, pageSize);
        query.setSimplePage(page);
        List<AdminCallLogVO> list = callLogReadMapper.selectCallLogList(query);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }
}
