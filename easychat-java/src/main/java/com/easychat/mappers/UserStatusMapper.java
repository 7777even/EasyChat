package com.easychat.mappers;

import com.easychat.entity.po.UserStatus;
import com.easychat.entity.query.UserStatusQuery;
import org.apache.ibatis.annotations.Param;

/**
 * 用户状态 数据库操作接口
 */
public interface UserStatusMapper<T, P> extends BaseMapper<T, P> {

    /**
     * 根据用户ID查询状态
     */
    T selectByUserId(@Param("userId") String userId);

    /**
     * 根据用户ID删除状态
     */
    Integer deleteByUserId(@Param("userId") String userId);
}
