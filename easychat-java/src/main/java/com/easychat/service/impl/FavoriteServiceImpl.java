package com.easychat.service.impl;

import com.easychat.entity.po.Favorite;
import com.easychat.entity.query.FavoriteQuery;
import com.easychat.entity.vo.FavoriteVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.FavoriteMapper;
import com.easychat.service.FavoriteService;
import com.easychat.utils.CopyTools;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 收藏业务实现
 */
@Service("favoriteService")
public class FavoriteServiceImpl implements FavoriteService {

    @Resource
    private FavoriteMapper<Favorite, FavoriteQuery> favoriteMapper;

    @Override
    public void addFavorite(String userId, Long messageId, String content, String filePath) {
        // 检查是否已收藏
        FavoriteQuery query = new FavoriteQuery();
        query.setUserId(userId);
        query.setMessageId(messageId);
        Integer count = favoriteMapper.selectCount(query);
        if (count != null && count > 0) {
            throw new BusinessException("该消息已收藏");
        }
        // 添加收藏
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setMessageId(messageId);
        favorite.setContent(content);
        favorite.setFilePath(filePath);
        favorite.setCreateTime(System.currentTimeMillis());
        favoriteMapper.insert(favorite);
    }

    @Override
    public void cancelFavorite(String userId, Long favoriteId) {
        if (favoriteId == null) {
            throw new BusinessException("收藏ID不能为空");
        }
        // 查询收藏
        FavoriteQuery query = new FavoriteQuery();
        query.setUserId(userId);
        List<Favorite> favoriteList = favoriteMapper.selectList(query);
        Favorite favorite = favoriteList.stream()
                .filter(f -> f.getId().equals(favoriteId))
                .findFirst()
                .orElse(null);
        if (favorite == null) {
            throw new BusinessException("收藏不存在");
        }
        // 删除收藏
        favoriteMapper.deleteById(favoriteId);
    }

    @Override
    public List<FavoriteVO> listFavorite(String userId) {
        FavoriteQuery query = new FavoriteQuery();
        query.setUserId(userId);
        List<Favorite> favoriteList = favoriteMapper.selectList(query);
        if (favoriteList == null) {
            return new ArrayList<>();
        }
        return favoriteList.stream()
                .map(favorite -> CopyTools.copy(favorite, FavoriteVO.class))
                .collect(Collectors.toList());
    }
}
