package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.Neo4jSyncTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Neo4j 全量同步任务进度 Mapper
 */
@Mapper
public interface Neo4jSyncTaskMapper extends BaseMapper<Neo4jSyncTaskEntity> {
}