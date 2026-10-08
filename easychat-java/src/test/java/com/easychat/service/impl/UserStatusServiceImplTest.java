package com.easychat.service.impl;

import com.easychat.entity.po.UserStatus;
import com.easychat.mappers.UserStatusMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserStatusServiceImpl 单元测试。
 *
 * <p>用户状态的「24 小时有效期」与「过期即删」是其核心契约：
 * setStatus 先删旧再插新（替换语义，非追加）、有效期恰为 24 小时；
 * getStatus 对过期状态惰性删除并返回 null，过期判定方向反了会让
 * 状态永不失效或立即失效。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class UserStatusServiceImplTest {

    private static final String USER = "U_me";
    private static final long ONE_HOUR_MS = 60L * 60 * 1000;

    @InjectMocks
    private UserStatusServiceImpl userStatusService;

    @Mock
    private UserStatusMapper<UserStatus, Object> userStatusMapper;

    @Test
    public void setStatus_replacesOldAndSets24hExpiry() {
        userStatusService.setStatus(USER, "动态内容", "/img/1.png");

        // 替换语义：先删旧状态，再插新状态
        org.mockito.InOrder inOrder = Mockito.inOrder(userStatusMapper);
        inOrder.verify(userStatusMapper).deleteByUserId(USER);
        ArgumentCaptor<UserStatus> captor = ArgumentCaptor.forClass(UserStatus.class);
        inOrder.verify(userStatusMapper).insert(captor.capture());

        UserStatus inserted = captor.getValue();
        assertEquals(USER, inserted.getUserId());
        assertEquals("动态内容", inserted.getContent());
        assertEquals("/img/1.png", inserted.getImageUrl());
        assertNotNull("创建时间由服务端填充", inserted.getCreateTime());
        assertNotNull("过期时间由服务端填充", inserted.getExpireTime());
        // 有效期恰为 24 小时
        assertEquals(24L * 60 * 60 * 1000,
                inserted.getExpireTime() - inserted.getCreateTime());
    }

    @Test
    public void getStatus_notExpired_returnsAsIs() {
        UserStatus status = new UserStatus();
        status.setUserId(USER);
        status.setContent("动态内容");
        status.setExpireTime(System.currentTimeMillis() + ONE_HOUR_MS);
        when(userStatusMapper.selectByUserId(USER)).thenReturn(status);

        assertEquals(status, userStatusService.getStatus(USER));
        // 未过期：不触发删除
        verify(userStatusMapper, never()).deleteByUserId(anyString());
    }

    @Test
    public void getStatus_expired_deletesAndReturnsNull() {
        UserStatus status = new UserStatus();
        status.setUserId(USER);
        status.setContent("过期内容");
        status.setExpireTime(System.currentTimeMillis() - 1000L);
        when(userStatusMapper.selectByUserId(USER)).thenReturn(status);

        assertNull("过期状态应返回 null", userStatusService.getStatus(USER));
        // 惰性删除：过期状态读出后立即清理
        verify(userStatusMapper).deleteByUserId(USER);
    }

    @Test
    public void getStatus_nullExpireTime_returnsAsIs() {
        // 历史数据 expireTime 为 null：不判定过期，原样返回（不 NPE）
        UserStatus status = new UserStatus();
        status.setUserId(USER);
        status.setContent("无过期时间的内容");
        when(userStatusMapper.selectByUserId(USER)).thenReturn(status);

        assertEquals(status, userStatusService.getStatus(USER));
        verify(userStatusMapper, never()).deleteByUserId(anyString());
    }

    @Test
    public void getStatus_missing_returnsNull() {
        when(userStatusMapper.selectByUserId(USER)).thenReturn(null);
        assertNull(userStatusService.getStatus(USER));
    }

    @Test
    public void clearStatus_deletesOldStatus() {
        userStatusService.clearStatus(USER);
        verify(userStatusMapper).deleteByUserId(USER);
    }
}
