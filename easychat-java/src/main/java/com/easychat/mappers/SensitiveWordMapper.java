package com.easychat.mappers;

import com.easychat.entity.po.SensitiveWord;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SensitiveWordMapper {

    /**
     * 查询指定状态的敏感词（启动时加载启用词）
     */
    List<SensitiveWord> selectByStatus(@Param("status") Integer status);
}
