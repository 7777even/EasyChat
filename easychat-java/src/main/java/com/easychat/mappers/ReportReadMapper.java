package com.easychat.mappers;

import com.easychat.entity.query.ReportQuery;
import com.easychat.entity.vo.AdminReportVO;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportReadMapper {

    /**
     * 举报统一视图：跨 moment_report / message_report 的分页计数
     */
    Integer selectReportCount(@Param("query") ReportQuery query);

    /**
     * 举报统一视图：跨 moment_report / message_report 的分页列表
     */
    List<AdminReportVO> selectReportList(@Param("query") ReportQuery query);

    /**
     * 按举报ID + 类型取单条（详情用）
     */
    AdminReportVO selectReportById(@Param("reportId") Long reportId,
                                    @Param("reportType") Integer reportType);
}
