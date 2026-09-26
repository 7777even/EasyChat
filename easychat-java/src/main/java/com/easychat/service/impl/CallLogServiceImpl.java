package com.easychat.service.impl;

import com.easychat.entity.po.CallLog;
import com.easychat.entity.query.CallLogQuery;
import com.easychat.mappers.CallLogMapper;
import com.easychat.service.CallLogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service("callLogService")
public class CallLogServiceImpl implements CallLogService {

    @Resource
    private CallLogMapper callLogMapper;

    @Override
    public Integer save(CallLog callLog) {
        return callLogMapper.insert(callLog);
    }

    @Override
    public List<CallLog> findList(CallLogQuery query) {
        return callLogMapper.selectList(query);
    }
}
