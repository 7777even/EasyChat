package com.easychat.service.impl;

import com.easychat.entity.po.Favorite;
import com.easychat.entity.query.FavoriteQuery;
import com.easychat.entity.vo.FavoriteVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.FavoriteMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FavoriteServiceImpl 单元测试。
 *
 * <p>收藏的「重复收藏幂等 / 取消归属」是易静默失效的面：
 * 重复收藏应拒绝、取消只能删自己的收藏（按 userId 过滤后再匹配 id）。
 * 此前为零覆盖（system-facts §14 #7）。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class FavoriteServiceImplTest {

    private static final String USER = "U_me";
    private static final Long MESSAGE_ID = 5001L;
    private static final Long FAVORITE_ID = 9001L;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

    @Mock
    private FavoriteMapper<Favorite, FavoriteQuery> favoriteMapper;

    private Favorite favoriteOf(Long id, Long messageId) {
        Favorite favorite = new Favorite();
        favorite.setId(id);
        favorite.setUserId(USER);
        favorite.setMessageId(messageId);
        favorite.setContent("收藏内容");
        favorite.setFilePath("/file/5001.jpg");
        favorite.setCreateTime(System.currentTimeMillis());
        return favorite;
    }

    // ======================== addFavorite ========================

    @Test
    public void addFavorite_duplicate_throwsWithMessage() {
        FavoriteQuery query = new FavoriteQuery();
        query.setUserId(USER);
        query.setMessageId(MESSAGE_ID);
        when(favoriteMapper.selectCount(any(FavoriteQuery.class))).thenReturn(1);
        try {
            favoriteService.addFavorite(USER, MESSAGE_ID, "内容", "/file/5001.jpg");
            fail("重复收藏应拒绝");
        } catch (BusinessException e) {
            assertEquals("该消息已收藏", e.getMessage());
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
        verify(favoriteMapper, never()).insert(any(Favorite.class));
    }

    @Test
    public void addFavorite_success_insertsFields() {
        when(favoriteMapper.selectCount(any(FavoriteQuery.class))).thenReturn(0);
        favoriteService.addFavorite(USER, MESSAGE_ID, "收藏内容", "/file/5001.jpg");

        ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
        verify(favoriteMapper).insert(captor.capture());
        Favorite inserted = captor.getValue();
        assertEquals(USER, inserted.getUserId());
        assertEquals(MESSAGE_ID, inserted.getMessageId());
        assertEquals("收藏内容", inserted.getContent());
        assertEquals("/file/5001.jpg", inserted.getFilePath());
        assertNotNull("创建时间由服务端填充", inserted.getCreateTime());
    }

    // ======================== cancelFavorite ========================

    @Test
    public void cancelFavorite_nullId_throwsWithMessage() {
        try {
            favoriteService.cancelFavorite(USER, null);
            fail("收藏ID为空应拒绝");
        } catch (BusinessException e) {
            assertEquals("收藏ID不能为空", e.getMessage());
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
    }

    @Test
    public void cancelFavorite_notOwned_throwsWithMessage() {
        // 列表中只有别人的收藏ID：按 userId 过滤后匹配不到 → 收藏不存在
        when(favoriteMapper.selectList(any(FavoriteQuery.class)))
                .thenReturn(Collections.singletonList(favoriteOf(FAVORITE_ID + 1, MESSAGE_ID)));
        try {
            favoriteService.cancelFavorite(USER, FAVORITE_ID);
            fail("取消不存在的收藏应拒绝");
        } catch (BusinessException e) {
            assertEquals("收藏不存在", e.getMessage());
            assertEquals(Integer.valueOf(1001), e.getCode());
        }
        verify(favoriteMapper, never()).deleteById(any(Long.class));
    }

    @Test
    public void cancelFavorite_success_deletesMatchingId() {
        List<Favorite> mine = Arrays.asList(
                favoriteOf(FAVORITE_ID - 1, MESSAGE_ID - 1),
                favoriteOf(FAVORITE_ID, MESSAGE_ID),
                favoriteOf(FAVORITE_ID + 1, MESSAGE_ID + 1));
        when(favoriteMapper.selectList(any(FavoriteQuery.class))).thenReturn(mine);

        favoriteService.cancelFavorite(USER, FAVORITE_ID);

        // 只删除匹配的那一条
        verify(favoriteMapper).deleteById(FAVORITE_ID);
    }

    // ======================== listFavorite ========================

    @Test
    public void listFavorite_nullList_returnsEmpty() {
        when(favoriteMapper.selectList(any(FavoriteQuery.class))).thenReturn(null);
        List<FavoriteVO> result = favoriteService.listFavorite(USER);
        assertTrue("null 列表应回落为空列表", result.isEmpty());
    }

    @Test
    public void listFavorite_success_returnsVOs() {
        when(favoriteMapper.selectList(any(FavoriteQuery.class)))
                .thenReturn(Arrays.asList(favoriteOf(FAVORITE_ID, MESSAGE_ID),
                        favoriteOf(FAVORITE_ID + 1, MESSAGE_ID + 1)));
        List<FavoriteVO> result = favoriteService.listFavorite(USER);
        assertEquals(2, result.size());
        assertEquals(MESSAGE_ID, result.get(0).getMessageId());
        assertEquals("收藏内容", result.get(0).getContent());
        assertEquals("/file/5001.jpg", result.get(0).getFilePath());
        assertEquals(FAVORITE_ID, result.get(0).getId());
    }
}
