package com.easychat.mappers;

import com.easychat.entity.po.SensitiveWord;
import com.easychat.entity.query.SensitiveWordQuery;
import com.easychat.entity.vo.SensitiveWordVO;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SensitiveWordMapper {

    /**
     * 查询指定状态的存活敏感词（启动时加载启用词，delete_flag=0）
     */
    List<SensitiveWord> selectByStatus(@Param("status") Integer status);

    /**
     * 存活词条分页计数（delete_flag=0）
     */
    Integer selectCount(@Param("query") SensitiveWordQuery query);

    /**
     * 存活词条分页列表（delete_flag=0）
     */
    List<SensitiveWordVO> selectList(@Param("query") SensitiveWordQuery query);

    /**
     * 按词查存活词条（新增/编辑/导入查重，delete_flag=0）
     */
    SensitiveWord selectAliveByWord(@Param("word") String word);

    /**
     * 按ID查存活词条（删除校验）
     */
    SensitiveWord selectAliveById(@Param("id") Long id);

    /**
     * 新增词条（delete_flag 默认 0）
     */
    Integer insert(@Param("bean") SensitiveWord bean);

    /**
     * 批量新增词条（导入用，delete_flag 默认 0）
     */
    Integer insertBatch(@Param("list") List<SensitiveWord> list);

    /**
     * 编辑词条（按ID）
     */
    Integer updateById(@Param("bean") SensitiveWord bean, @Param("id") Long id);

    /**
     * 逻辑删除：置 delete_flag 为删除时间戳（仅存活行生效）
     */
    Integer deleteById(@Param("id") Long id, @Param("deleteFlag") Long deleteFlag);

    /**
     * 导出用：全部存活词条（不分页，按创建时间升序）
     */
    List<SensitiveWord> selectAllAlive();
}
