package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

public interface MomentNotifyMapper<T, P> extends BaseMapper<T, P> {

    /**
     * 将指定用户的未读通知批量标记为已读
     *
     * @param userId 收件人
     * @param type   通知类型，为 null 表示全部类型
     */
    Integer markReadByUserId(@Param("userId") String userId, @Param("type") Integer type);

    Integer updateById(@Param("bean") T t, @Param("id") Long id);

    Integer deleteById(@Param("id") Long id);

    T selectById(@Param("id") Long id);
}

