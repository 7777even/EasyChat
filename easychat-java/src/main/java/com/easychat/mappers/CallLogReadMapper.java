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
     * 通话记录管理端列表：分页行（固定按 cl.id desc 排序为 XML 内字面量，不引用任何排序字段）。
     * 2026-10-06：BaseParam 的 orderBy 字段已整体移除，排序统一走 sortOption 白名单三件套，
     * 本 Mapper 刻意不接入 —— 它的排序是固定的，且 cl.id 里的表别名只在此 SQL 内有效。
     */
    List<AdminCallLogVO> selectCallLogList(@Param("query") CallLogQuery query);
}
