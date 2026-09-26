package com.easychat.mappers;

import com.easychat.entity.po.ReportAuditLog;
import com.easychat.entity.query.ReportAuditQuery;
import com.easychat.entity.vo.ReportAuditLogVO;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportAuditLogMapper {

    /**
     * 写入一条审计记录
     */
    Integer insert(ReportAuditLog log);

    /**
     * 审计日志分页计数
     */
    Integer selectCount(@Param("query") ReportAuditQuery query);

    /**
     * 审计日志分页列表
     */
    List<ReportAuditLogVO> selectList(@Param("query") ReportAuditQuery query);
}
