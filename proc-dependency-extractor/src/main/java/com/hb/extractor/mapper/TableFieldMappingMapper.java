package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableFieldMapping;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字段级血缘映射 Mapper
 */
@Mapper
public interface TableFieldMappingMapper extends BaseMapper<TableFieldMapping> {
}
