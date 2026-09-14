package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.RebuildTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 全量重新解析任务进度 Mapper
 */
@Mapper
public interface RebuildTaskMapper extends BaseMapper<RebuildTaskEntity> {
}