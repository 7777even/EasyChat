package com.easychat.service;

import com.easychat.entity.vo.FavoriteVO;

import java.util.List;

/**
 * 收藏业务接口
 */
public interface FavoriteService {

    /**
     * 添加收藏
     *
     * @param userId 用户 ID
     * @param messageId 消息 ID
     * @param content 收藏内容
     * @param filePath 文件路径
     */
    void addFavorite(String userId, Long messageId, String content, String filePath);

    /**
     * 取消收藏
     *
     * @param userId 用户 ID
     * @param favoriteId 收藏 ID
     */
    void cancelFavorite(String userId, Long favoriteId);

    /**
     * 查询收藏列表
     *
     * @param userId 用户 ID
     * @return 收藏列表
     */
    List<FavoriteVO> listFavorite(String userId);
}
