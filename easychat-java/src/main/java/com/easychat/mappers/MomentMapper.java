package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 朋友圈动态 数据库操作接口
 */
public interface MomentMapper<T, P> extends BaseMapper<T, P> {

    Integer updateById(@Param("bean") T t, @Param("id") Long id);

    Integer deleteById(@Param("id") Long id);

    T selectById(@Param("id") Long id);
}

