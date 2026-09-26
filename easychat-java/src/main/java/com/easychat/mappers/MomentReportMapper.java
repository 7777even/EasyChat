package com.easychat.mappers;

import com.easychat.entity.po.MomentReport;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MomentReportMapper {

    /**
     * 查询同一举报人对同一对象、状态为待处理（0）的举报数（幂等判定）
     */
    Integer countPending(@Param("reportUserId") String reportUserId,
                          @Param("momentId") Long momentId,
                          @Param("commentId") Long commentId);

    /**
     * 插入举报记录
     */
    Integer insert(@Param("bean") MomentReport report);

    /**
     * 按ID更新处理字段（status/handle_user_id/handle_time/handle_note/handle_action）
     */
    Integer updateById(@Param("bean") MomentReport report, @Param("id") Long id);
}
