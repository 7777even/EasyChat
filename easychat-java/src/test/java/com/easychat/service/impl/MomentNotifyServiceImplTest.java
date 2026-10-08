package com.easychat.service.impl;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.SortOption;
import com.easychat.entity.po.Moment;
import com.easychat.entity.po.MomentNotify;
import com.easychat.entity.po.UserInfo;
import com.easychat.entity.query.MomentNotifyQuery;
import com.easychat.entity.vo.MomentNotifyVO;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.mappers.MomentMapper;
import com.easychat.mappers.MomentNotifyMapper;
import com.easychat.mappers.UserInfoMapper;
import com.easychat.websocket.ChannelContextUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MomentNotifyServiceImpl 单元测试。
 *
 * <p>朋友圈通知是「落库 + WS 实时帧」双写面：
 * 通知类型 → 帧号映射（15/16/17/18）、自动作不
 * 通知自己、写入失败不影响主流程、未读数变化
 * 广播帧（-8）、列表分页（默认 20、上限 40、
 * offset 分页）与通知摘要（类型前缀 + 动态正文
 * 20 字截断）。此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class MomentNotifyServiceImplTest {

    private static final String USER = "U_me";
    private static final String FROM_USER = "U_from";
    private static final Long REF_ID = 9001L;
    private static final String CONTENT = "评论内容";
    private static final Integer UNREAD = 3;

    @InjectMocks
    private MomentNotifyServiceImpl momentNotifyService;

    @Mock
    private MomentNotifyMapper<MomentNotify, MomentNotifyQuery> momentNotifyMapper;

    @Mock
    private UserInfoMapper<UserInfo, com.easychat.entity.query.UserInfoQuery> userInfoMapper;

    @Mock
    private MomentMapper<Moment, com.easychat.entity.query.MomentQuery> momentMapper;

    @Mock
    private ChannelContextUtils channelContextUtils;

    private UserInfo userOf(String nickName) {
        UserInfo user = new UserInfo();
        user.setUserId(FROM_USER);
        user.setNickName(nickName);
        return user;
    }

    private MomentNotify notifyOf(Integer type, Long refId) {
        MomentNotify notify = new MomentNotify();
        notify.setId(7001L);
        notify.setUserId(USER);
        notify.setType(type);
        notify.setRefId(refId);
        notify.setFromUserId(FROM_USER);
        notify.setReadStatus(0);
        notify.setCreateTime(System.currentTimeMillis());
        return notify;
    }

    private void stubCommon() {
        when(userInfoMapper.selectByUserId(FROM_USER)).thenReturn(userOf("发送者"));
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class)))
                .thenReturn(UNREAD);
    }

    // ======================== pushNotify：守卫 ========================

    @Test
    public void pushNotify_emptyUserId_silentReturn() {
        momentNotifyService.pushNotify("", 1, REF_ID, FROM_USER, CONTENT);
        verify(momentNotifyMapper, never()).insert(any(MomentNotify.class));
        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
    }

    @Test
    public void pushNotify_nullType_silentReturn() {
        momentNotifyService.pushNotify(USER, null, REF_ID, FROM_USER, CONTENT);
        verify(momentNotifyMapper, never()).insert(any(MomentNotify.class));
    }

    @Test
    public void pushNotify_selfAction_silentReturn() {
        // 自己触发的动作不给自己发通知
        momentNotifyService.pushNotify(USER, 1, REF_ID, USER, CONTENT);
        verify(momentNotifyMapper, never()).insert(any(MomentNotify.class));
        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
    }

    @Test
    public void pushNotify_insertFailure_silentReturn() {
        // 通知写入失败不影响主流程（动态/点赞/评论已成功）：只记日志，不推帧
        doThrow(new RuntimeException("db down"))
                .when(momentNotifyMapper).insert(any(MomentNotify.class));
        momentNotifyService.pushNotify(USER, 1, REF_ID, FROM_USER, CONTENT);
        verify(channelContextUtils, never()).sendMessage(any(MessageSendDto.class));
    }

    // ======================== pushNotify：落库与推帧 ========================

    @Test
    public void pushNotify_like_persistsAndFrames() {
        stubCommon();

        momentNotifyService.pushNotify(USER, 1, REF_ID, FROM_USER, CONTENT);

        // 落库：未读状态 + 服务端创建时间
        ArgumentCaptor<MomentNotify> notifyCaptor = ArgumentCaptor.forClass(MomentNotify.class);
        verify(momentNotifyMapper).insert(notifyCaptor.capture());
        MomentNotify persisted = notifyCaptor.getValue();
        assertEquals(USER, persisted.getUserId());
        assertEquals(Integer.valueOf(1), persisted.getType());
        assertEquals(REF_ID, persisted.getRefId());
        assertEquals(FROM_USER, persisted.getFromUserId());
        assertEquals(Integer.valueOf(0), persisted.getReadStatus());
        assertNotNull("创建时间由服务端填充", persisted.getCreateTime());

        // 推帧：点赞 → 16 帧，联系人=接收者，发送者=触发者
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dtoCaptor.capture());
        MessageSendDto sent = dtoCaptor.getValue();
        assertEquals(MessageTypeEnum.MOMENT_LIKE.getType(), sent.getMessageType());
        assertEquals(USER, sent.getContactId());
        assertEquals(FROM_USER, sent.getSendUserId());
        assertEquals(CONTENT, sent.getMessageContent());
        assertEquals(persisted.getCreateTime(), sent.getSendTime());

        // 扩展数据：通知类型 / 关联ID / 内容 / 发送者昵称 / 当前未读数
        @SuppressWarnings("unchecked")
        Map<String, Object> extendData = (Map<String, Object>) sent.getExtendData();
        assertTrue(extendData.containsKey("notifyId"));
        assertEquals(1, extendData.get("notifyType"));
        assertEquals(REF_ID, extendData.get("refId"));
        assertEquals(CONTENT, extendData.get("content"));
        assertEquals("发送者", extendData.get("fromNickName"));
        assertEquals(UNREAD, extendData.get("unreadCount"));
    }

    @Test
    public void pushNotify_commentReply_usesCommentFrame() {
        // 类型 3（回复评论）与类型 2（评论）共用 17 帧
        stubCommon();
        momentNotifyService.pushNotify(USER, 3, REF_ID, FROM_USER, CONTENT);
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dtoCaptor.capture());
        assertEquals(MessageTypeEnum.MOMENT_COMMENT.getType(),
                dtoCaptor.getValue().getMessageType());
    }

    @Test
    public void pushNotify_at_usesAtFrame() {
        stubCommon();
        momentNotifyService.pushNotify(USER, 4, REF_ID, FROM_USER, CONTENT);
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dtoCaptor.capture());
        assertEquals(MessageTypeEnum.MOMENT_AT.getType(), dtoCaptor.getValue().getMessageType());
    }

    @Test
    public void pushNotify_unknownType_fallsBackToNewFrame() {
        // 未登记类型 → 兜底 15 帧（新动态），不丢通知
        stubCommon();
        momentNotifyService.pushNotify(USER, 99, REF_ID, FROM_USER, CONTENT);
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dtoCaptor.capture());
        assertEquals(MessageTypeEnum.MOMENT_NEW.getType(), dtoCaptor.getValue().getMessageType());
    }

    // ======================== getUnreadCount ========================

    @Test
    public void getUnreadCount_nullCount_returnsZero() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(null);
        assertEquals(Integer.valueOf(0), momentNotifyService.getUnreadCount(USER));
    }

    @Test
    public void getUnreadCount_success() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(7);
        assertEquals(Integer.valueOf(7), momentNotifyService.getUnreadCount(USER));
    }

    // ======================== loadNotifyList ========================

    @Test
    public void loadNotifyList_invalidPaging_fallsBackToDefaultsAndCap() {
        // 页码 0 / 负数 → 第 1 页；pageSize 超过上限 40 → 截断为 40
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(45);
        when(momentNotifyMapper.selectList(any(MomentNotifyQuery.class)))
                .thenReturn(Collections.emptyList());

        PaginationResultVO<MomentNotifyVO> result =
                momentNotifyService.loadNotifyList(USER, -1, 999);

        ArgumentCaptor<MomentNotifyQuery> queryCaptor = ArgumentCaptor.forClass(MomentNotifyQuery.class);
        verify(momentNotifyMapper).selectCount(queryCaptor.capture());
        MomentNotifyQuery query = queryCaptor.getValue();
        assertEquals(USER, query.getUserId());
        assertEquals(SortOption.MOMENT_NOTIFY_CREATE_TIME_DESC, query.getSortOption());
        // offset 分页：start=0、end=40
        assertEquals(0, query.getSimplePage().getStart());
        assertEquals(40, query.getSimplePage().getEnd());

        assertEquals(Integer.valueOf(45), result.getTotalCount());
        assertEquals(Integer.valueOf(40), result.getPageSize());
        assertEquals(Integer.valueOf(1), result.getPageNo());
        // pageTotal = ceil(45/40) = 2
        assertEquals(Integer.valueOf(2), result.getPageTotal());
    }

    @Test
    public void loadNotifyList_offsetPaging() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(100);
        when(momentNotifyMapper.selectList(any(MomentNotifyQuery.class)))
                .thenReturn(Collections.emptyList());

        PaginationResultVO<MomentNotifyVO> result =
                momentNotifyService.loadNotifyList(USER, 3, 10);

        ArgumentCaptor<MomentNotifyQuery> queryCaptor = ArgumentCaptor.forClass(MomentNotifyQuery.class);
        verify(momentNotifyMapper).selectList(queryCaptor.capture());
        // 第 3 页每页 10 条 → offset = (3-1)*10 = 20
        assertEquals(20, queryCaptor.getValue().getSimplePage().getStart());
        assertEquals(10, queryCaptor.getValue().getSimplePage().getEnd());
        assertEquals(Integer.valueOf(3), result.getPageNo());
        assertEquals(Integer.valueOf(10), result.getPageSize());
        assertEquals(Integer.valueOf(10), result.getPageTotal());
    }

    @Test
    public void loadNotifyList_buildsContentWithBriefAndTruncation() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(1);
        MomentNotify notify = notifyOf(2, REF_ID);
        when(momentNotifyMapper.selectList(any(MomentNotifyQuery.class)))
                .thenReturn(Collections.singletonList(notify));
        // 动态正文超长 → 截断 20 字 + 省略号
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longContent.append(i % 10);
        }
        Moment moment = new Moment();
        moment.setId(REF_ID);
        moment.setContent(longContent.toString());
        when(momentMapper.selectById(REF_ID)).thenReturn(moment);
        when(userInfoMapper.selectByUserId(FROM_USER)).thenReturn(userOf("发送者"));

        PaginationResultVO<MomentNotifyVO> result =
                momentNotifyService.loadNotifyList(USER, 1, 20);

        assertEquals(1, result.getList().size());
        MomentNotifyVO vo = result.getList().get(0);
        assertEquals(notify.getId(), vo.getId());
        assertEquals(Integer.valueOf(2), vo.getType());
        assertEquals(REF_ID, vo.getRefId());
        assertEquals(FROM_USER, vo.getFromUserId());
        assertEquals("发送者", vo.getFromNickName());
        assertEquals(FROM_USER, vo.getFromAvatar());
        assertEquals(Integer.valueOf(0), vo.getReadStatus());
        // 摘要 = 类型前缀 + 截断后的动态正文
        assertEquals("评论了你的动态：01234567890123456789…", vo.getContent());
    }

    // ======================== loadRecentNotify ========================

    @Test
    public void loadRecentNotify_defaultLimit5() {
        when(momentNotifyMapper.selectList(any(MomentNotifyQuery.class)))
                .thenReturn(Collections.emptyList());

        momentNotifyService.loadRecentNotify(USER, null);

        ArgumentCaptor<MomentNotifyQuery> queryCaptor = ArgumentCaptor.forClass(MomentNotifyQuery.class);
        verify(momentNotifyMapper).selectList(queryCaptor.capture());
        MomentNotifyQuery query = queryCaptor.getValue();
        assertEquals(SortOption.MOMENT_NOTIFY_CREATE_TIME_DESC, query.getSortOption());
        // limit 未传 → 默认最近 5 条
        assertEquals(0, query.getSimplePage().getStart());
        assertEquals(5, query.getSimplePage().getEnd());
    }

    // ======================== 已读 / 清空 ========================

    @Test
    public void markAllRead_marksAndNotifiesUnreadChange() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(0);

        momentNotifyService.markAllRead(USER);

        // 全部标记已读（type=null 表示全部类型）
        verify(momentNotifyMapper).markReadByUserId(USER, null);
        // 未读数变化广播帧（-8）
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dtoCaptor.capture());
        MessageSendDto sent = dtoCaptor.getValue();
        assertEquals(Constants.WS_MOMENT_UNREAD_MESSAGE_TYPE, sent.getMessageType());
        assertEquals(USER, sent.getContactId());
        @SuppressWarnings("unchecked")
        Map<String, Object> extendData = (Map<String, Object>) sent.getExtendData();
        assertEquals(0, extendData.get("unreadCount"));
    }

    @Test
    public void markReadByType_marksAndNotifiesUnreadChange() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(2);

        momentNotifyService.markReadByType(USER, 1);

        verify(momentNotifyMapper).markReadByUserId(USER, 1);
        ArgumentCaptor<MessageSendDto> dtoCaptor = ArgumentCaptor.forClass(MessageSendDto.class);
        verify(channelContextUtils).sendMessage(dtoCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> extendData = (Map<String, Object>) dtoCaptor.getValue().getExtendData();
        assertEquals(2, extendData.get("unreadCount"));
    }

    @Test
    public void markRead_updatesByParamAndNotifiesUnreadChange() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(4);

        momentNotifyService.markRead(USER, 7001L);

        // 单条标记已读：条件锁定 notifyId + userId，防越权改他人通知
        ArgumentCaptor<MomentNotify> beanCaptor = ArgumentCaptor.forClass(MomentNotify.class);
        ArgumentCaptor<MomentNotifyQuery> queryCaptor = ArgumentCaptor.forClass(MomentNotifyQuery.class);
        verify(momentNotifyMapper).updateByParam(beanCaptor.capture(), queryCaptor.capture());
        assertEquals(Integer.valueOf(1), beanCaptor.getValue().getReadStatus());
        assertEquals(USER, queryCaptor.getValue().getUserId());
        assertEquals(Long.valueOf(7001L), queryCaptor.getValue().getId());
        verify(channelContextUtils).sendMessage(any(MessageSendDto.class));
    }

    @Test
    public void clearNotify_deletesAndNotifiesUnreadChange() {
        when(momentNotifyMapper.selectCount(any(MomentNotifyQuery.class))).thenReturn(0);

        momentNotifyService.clearNotify(USER);

        ArgumentCaptor<MomentNotifyQuery> queryCaptor = ArgumentCaptor.forClass(MomentNotifyQuery.class);
        verify(momentNotifyMapper).deleteByParam(queryCaptor.capture());
        assertEquals(USER, queryCaptor.getValue().getUserId());
        verify(channelContextUtils).sendMessage(any(MessageSendDto.class));
    }
}
