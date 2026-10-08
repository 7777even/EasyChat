package com.easychat.service.impl;

import com.easychat.entity.enums.PageSize;
import com.easychat.entity.query.CallLogQuery;
import com.easychat.entity.vo.AdminCallLogVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.mappers.CallLogReadMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AdminCallLogServiceImpl 单元测试。
 *
 * <p>管理端通话记录分页查询：锁定分页形态与默认每页 15 条
 * （排序由 XML 字面量 ORDER BY cl.id DESC 固定，不接受请求传入）。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AdminCallLogServiceImplTest {

    @InjectMocks
    private AdminCallLogServiceImpl adminCallLogService;

    @Mock
    private CallLogReadMapper callLogReadMapper;

    @Test
    public void loadCallLog_success_defaultPageSize15() {
        when(callLogReadMapper.selectCallLogCount(any(CallLogQuery.class))).thenReturn(9);
        when(callLogReadMapper.selectCallLogList(any(CallLogQuery.class)))
                .thenReturn(Collections.singletonList(new AdminCallLogVO()));

        // pageSize 未传 → 默认每页 15 条、页码默认 1
        CallLogQuery query = new CallLogQuery();
        PaginationResultVO<AdminCallLogVO> result = adminCallLogService.loadCallLog(query);

        assertEquals(Integer.valueOf(9), result.getTotalCount());
        assertEquals(Integer.valueOf(PageSize.SIZE15.getSize()), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertEquals(Integer.valueOf(1), result.getPageTotal());
        assertEquals(1, result.getList().size());
        // 分页参数回写查询对象
        assertNotNull(query.getSimplePage());
        assertEquals(PageSize.SIZE15.getSize(), query.getSimplePage().getPageSize());
    }
}
