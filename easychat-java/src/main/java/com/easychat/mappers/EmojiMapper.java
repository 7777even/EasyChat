package com.easychat.mappers;

import com.easychat.entity.po.Emoji;
import com.easychat.entity.query.EmojiQuery;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 表情包 Mapper
 */
public interface EmojiMapper<Emoji, EmojiQuery> {

    /**
     * 插入
     */
    int insert(Emoji emoji);

    /**
     * 批量插入
     */
    int insertBatch(List<Emoji> list);

    /**
     * 根据ID删除
     */
    int deleteById(@Param("id") Long id);

    /**
     * 根据条件查询列表
     */
    List<Emoji> selectList(EmojiQuery query);

    /**
     * 根据条件查询数量
     */
    Integer selectCount(EmojiQuery query);
}
