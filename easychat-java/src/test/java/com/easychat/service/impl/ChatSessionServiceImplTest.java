package com.easychat.service.impl;

import com.easychat.entity.po.ChatSession;
import com.easychat.entity.query.ChatSessionQuery;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.entity.vo.PaginationResultVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSessionServiceImpl 单元测试。
 *
 * <p>会话表是会话列表的数据底座：锁定批量接口的
 * 空集合短路（不触碰 Mapper）、多条件更新 / 删除的
 * 非空条件校验（checkParam，防全表更新 / 删除）、
 * 分页默认每页 15 条，以及按 sessionId 键控的读写委托。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class ChatSessionServiceImplTest {

    private static final String SESSION_ID = "S001";

    @InjectMocks
    private ChatSessionServiceImpl chatSessionService;

    @Mock
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    private ChatSession sessionOf(String sessionId) {
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setLastMessage("最近一条消息");
        session.setLastReceiveTime(System.currentTimeMillis());
        return session;
    }

    // ======================== 分页查询 ========================

    @Test
    public void findListByPage_success_defaultPageSize15() {
        when(chatSessionMapper.selectCount(any(ChatSessionQuery.class))).thenReturn(5);
        when(chatSessionMapper.selectList(any(ChatSessionQuery.class)))
                .thenReturn(Collections.singletonList(sessionOf(SESSION_ID)));

        // pageSize 未传 → 默认每页 15 条、页码默认 1
        ChatSessionQuery query = new ChatSessionQuery();
        PaginationResultVO<ChatSession> result = chatSessionService.findListByPage(query);

        assertEquals(Integer.valueOf(5), result.getTotalCount());
        assertEquals(Integer.valueOf(com.easychat.entity.enums.PageSize.SIZE15.getSize()),
                result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        assertEquals(Integer.valueOf(1), result.getPageTotal());
        assertEquals(1, result.getList().size());
        // 分页参数回写查询对象
        assertNotNull(query.getSimplePage());
        assertEquals(com.easychat.entity.enums.PageSize.SIZE15.getSize(),
                query.getSimplePage().getPageSize());
    }

    // ======================== 批量新增：空集合短路 ========================

    @Test
    public void addBatch_null_returnsZeroWithoutMapperCall() {
        assertEquals(Integer.valueOf(0), chatSessionService.addBatch(null));
        verify(chatSessionMapper, never()).insertBatch(anyList());
    }

    @Test
    public void addBatch_empty_returnsZeroWithoutMapperCall() {
        assertEquals(Integer.valueOf(0), chatSessionService.addBatch(Collections.emptyList()));
        verify(chatSessionMapper, never()).insertBatch(anyList());
    }

    @Test
    public void addBatch_delegatesToInsertBatch() {
        List<ChatSession> batch = Collections.singletonList(sessionOf(SESSION_ID));
        when(chatSessionMapper.insertBatch(batch)).thenReturn(1);
        assertEquals(Integer.valueOf(1), chatSessionService.addBatch(batch));
        verify(chatSessionMapper).insertBatch(batch);
    }

    @Test
    public void addOrUpdateBatch_null_returnsZeroWithoutMapperCall() {
        assertEquals(Integer.valueOf(0), chatSessionService.addOrUpdateBatch(null));
        verify(chatSessionMapper, never()).insertOrUpdateBatch(anyList());
    }

    @Test
    public void addOrUpdateBatch_delegatesToInsertOrUpdateBatch() {
        List<ChatSession> batch = Collections.singletonList(sessionOf(SESSION_ID));
        when(chatSessionMapper.insertOrUpdateBatch(batch)).thenReturn(1);
        assertEquals(Integer.valueOf(1), chatSessionService.addOrUpdateBatch(batch));
        verify(chatSessionMapper).insertOrUpdateBatch(batch);
    }

    // ======================== 多条件更新 / 删除：非空条件校验 ========================

    @Test
    public void updateByParam_emptyQuery_throwsWithMessage() {
        // 全空条件 → 拒绝，防全表更新
        try {
            chatSessionService.updateByParam(new ChatSession(), new ChatSessionQuery());
            fail("全空条件应拒绝");
        } catch (BusinessException e) {
            assertEquals("多参数更新，删除，必须有非空条件", e.getMessage());
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
        verify(chatSessionMapper, never()).updateByParam(any(ChatSession.class),
                any(ChatSessionQuery.class));
    }

    @Test
    public void updateByParam_withCondition_delegates() {
        ChatSessionQuery query = new ChatSessionQuery();
        query.setSessionId(SESSION_ID);
        when(chatSessionMapper.updateByParam(any(ChatSession.class), eq(query))).thenReturn(1);

        assertEquals(Integer.valueOf(1),
                chatSessionService.updateByParam(new ChatSession(), query));
        verify(chatSessionMapper).updateByParam(any(ChatSession.class), eq(query));
    }

    @Test
    public void deleteByParam_emptyQuery_throwsWithMessage() {
        // 全空条件 → 拒绝，防全表删除
        try {
            chatSessionService.deleteByParam(new ChatSessionQuery());
            fail("全空条件应拒绝");
        } catch (BusinessException e) {
            assertEquals("多参数更新，删除，必须有非空条件", e.getMessage());
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
        verify(chatSessionMapper, never()).deleteByParam(any(ChatSessionQuery.class));
    }

    @Test
    public void deleteByParam_withCondition_delegates() {
        ChatSessionQuery query = new ChatSessionQuery();
        query.setSessionId(SESSION_ID);
        when(chatSessionMapper.deleteByParam(query)).thenReturn(1);

        assertEquals(Integer.valueOf(1), chatSessionService.deleteByParam(query));
        verify(chatSessionMapper).deleteByParam(query);
    }

    // ======================== 按 sessionId 键控的读写 ========================

    @Test
    public void getChatSessionBySessionId_delegates() {
        ChatSession expected = sessionOf(SESSION_ID);
        when(chatSessionMapper.selectBySessionId(SESSION_ID)).thenReturn(expected);
        assertEquals(expected, chatSessionService.getChatSessionBySessionId(SESSION_ID));
        verify(chatSessionMapper).selectBySessionId(SESSION_ID);
    }

    @Test
    public void updateChatSessionBySessionId_delegates() {
        when(chatSessionMapper.updateBySessionId(any(ChatSession.class),
                eq(SESSION_ID))).thenReturn(1);
        assertEquals(Integer.valueOf(1),
                chatSessionService.updateChatSessionBySessionId(new ChatSession(), SESSION_ID));
        verify(chatSessionMapper).updateBySessionId(any(ChatSession.class), eq(SESSION_ID));
    }

    @Test
    public void deleteChatSessionBySessionId_delegates() {
        when(chatSessionMapper.deleteBySessionId(SESSION_ID)).thenReturn(1);
        assertEquals(Integer.valueOf(1), chatSessionService.deleteChatSessionBySessionId(SESSION_ID));
        verify(chatSessionMapper).deleteBySessionId(SESSION_ID);
    }

    // ======================== 其余委托 ========================

    @Test
    public void add_delegatesToInsert() {
        ChatSession session = sessionOf(SESSION_ID);
        when(chatSessionMapper.insert(session)).thenReturn(1);
        assertEquals(Integer.valueOf(1), chatSessionService.add(session));
        verify(chatSessionMapper).insert(session);
    }

    @Test
    public void findListByParam_delegates() {
        ChatSessionQuery query = new ChatSessionQuery();
        List<ChatSession> expected = Collections.singletonList(sessionOf(SESSION_ID));
        when(chatSessionMapper.selectList(query)).thenReturn(expected);
        assertEquals(expected, chatSessionService.findListByParam(query));
        verify(chatSessionMapper).selectList(query);
    }

    @Test
    public void findCountByParam_delegates() {
        ChatSessionQuery query = new ChatSessionQuery();
        when(chatSessionMapper.selectCount(query)).thenReturn(3);
        assertEquals(Integer.valueOf(3), chatSessionService.findCountByParam(query));
        verify(chatSessionMapper).selectCount(query);
    }
}
