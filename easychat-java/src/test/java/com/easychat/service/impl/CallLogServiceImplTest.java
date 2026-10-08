package com.easychat.service.impl;

import com.easychat.entity.po.CallLog;
import com.easychat.entity.query.CallLogQuery;
import com.easychat.mappers.CallLogMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CallLogServiceImpl 单元测试。
 *
 * <p>通话记录的写入入口（由通话流程 CallService 调用）：
 * 锁定 save / findList 与 Mapper 的委托契约。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class CallLogServiceImplTest {

    @InjectMocks
    private CallLogServiceImpl callLogService;

    @Mock
    private CallLogMapper callLogMapper;

    @Test
    public void save_delegatesToInsert() {
        CallLog callLog = new CallLog();
        callLog.setCallerId("U_caller");
        callLog.setPeerId("U_peer");
        callLog.setCallType(1);
        when(callLogMapper.insert(callLog)).thenReturn(1);

        assertEquals(Integer.valueOf(1), callLogService.save(callLog));
        verify(callLogMapper).insert(callLog);
    }

    @Test
    public void findList_delegatesToSelectList() {
        CallLogQuery query = new CallLogQuery();
        query.setCallerId("U_caller");
        List<CallLog> expected = Collections.singletonList(new CallLog());
        when(callLogMapper.selectList(query)).thenReturn(expected);

        assertEquals(expected, callLogService.findList(query));
        verify(callLogMapper).selectList(query);
    }
}
