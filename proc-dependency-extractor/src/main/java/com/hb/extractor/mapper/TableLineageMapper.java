package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableLineage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 表间派生血缘关系 Mapper
 */
@Mapper
public interface TableLineageMapper extends BaseMapper<TableLineage> {

    /**
     * 递归查询上游血缘（按全名匹配，避免跨集市同名表串线）
     * @param targetTableFull 目标表全名（schema.table）
     * @param maxDepth 最大深度
     * @return 血缘记录列表（含 depth 字段）
     */
    List<Map<String, Object>> findUpstream(@Param("targetTableFull") String targetTableFull,
                                            @Param("maxDepth") int maxDepth);

    /**
     * 递归查询下游血缘（按全名匹配，避免跨集市同名表串线）
     * @param sourceTableFull 源表全名（schema.table）
     * @param maxDepth 最大深度
     * @return 血缘记录列表（含 depth 字段）
     */
    List<Map<String, Object>> findDownstream(@Param("sourceTableFull") String sourceTableFull,
                                              @Param("maxDepth") int maxDepth);
}
