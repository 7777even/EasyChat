package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 群文件数据库操作接口
 */
public interface GroupFileMapper<T, P> extends BaseMapper<T, P> {

    /**
     * 根据ID获取对象
     */
    T selectById(@Param("id") Long id);
}
