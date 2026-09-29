package com.easychat.mappers;

import com.easychat.entity.query.CallLogQuery;
import com.easychat.entity.vo.AdminCallLogVO;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通话记录管理端只读查询（LEFT JOIN 昵称/群名）
 */
@Repository
public interface CallLogReadMapper {

    /**
     * 通话记录管理端列表：筛选条件计数
     */
    Integer selectCallLogCount(@Param("query") CallLogQuery query);

    /**
     * 通话记录管理端列表：分页行（固定按 cl.id desc 排序，不引用 query.orderBy）
     */
    List<AdminCallLogVO> selectCallLogList(@Param("query") CallLogQuery query);
}
