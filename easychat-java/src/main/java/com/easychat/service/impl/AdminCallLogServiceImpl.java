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
        // 2026-10-06：原有一行 `query.setOrderBy("cl.id desc")`，注释写「服务端硬编码覆盖，请求传入值一律丢弃」。
        //   实测该行**从未生效**：本方法用的是 callLogReadMapper，而 `CallLogReadMapper.xml` 的排序
        //   是 XML 内字面量 `ORDER BY cl.id DESC`（其第 84 行注释亦明说不引用 ${query.orderBy}），
        //   根本不读 orderBy。属遗留冗余，随 orderBy 字段一并移除。
        //   真正的注入面在 CallLogMapper.selectList，而该方法**全仓零调用**（死代码，见遗留 #23）。
        int count = callLogReadMapper.selectCallLogCount(query);
        int pageSize = query.getPageSize() == null ? PageSize.SIZE15.getSize() : query.getPageSize();
        SimplePage page = new SimplePage(query.getPageNo(), count, pageSize);
        query.setSimplePage(page);
        List<AdminCallLogVO> list = callLogReadMapper.selectCallLogList(query);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
    }
}
