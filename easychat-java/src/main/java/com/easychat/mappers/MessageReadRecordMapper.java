package com.easychat.mappers;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息已读/送达确认记录 数据库操作接口
 */
public interface MessageReadRecordMapper<T, P> extends BaseMapper<T, P> {

    /**
     * 批量插入或更新（幂等：(user_id, message_id) 唯一）
     */
    Integer insertOrUpdateBatch(@Param("list") List<T> list);
}
