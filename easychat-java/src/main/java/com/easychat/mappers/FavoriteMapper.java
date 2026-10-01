package com.easychat.mappers;

import com.easychat.entity.po.Favorite;
import com.easychat.entity.query.FavoriteQuery;

import java.util.List;

/**
 * 收藏 Mapper
 */
public interface FavoriteMapper<Favorite, FavoriteQuery> {

    /**
     * 插入
     */
    int insert(Favorite favorite);

    /**
     * 根据ID删除
     */
    int deleteById(Long id);

    /**
     * 根据条件查询列表
     */
    List<Favorite> selectList(FavoriteQuery query);

    /**
     * 根据条件查询数量
     */
    Integer selectCount(FavoriteQuery query);
}
