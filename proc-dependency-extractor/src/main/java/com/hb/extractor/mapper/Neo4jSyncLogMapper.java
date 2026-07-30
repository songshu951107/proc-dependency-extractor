package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.Neo4jSyncLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface Neo4jSyncLogMapper extends BaseMapper<Neo4jSyncLog> {
}
