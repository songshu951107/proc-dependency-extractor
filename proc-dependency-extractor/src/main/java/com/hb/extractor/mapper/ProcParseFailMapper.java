package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.ProcParseFail;
import org.apache.ibatis.annotations.Mapper;

/**
 * SQL 块解析失败清单 Mapper
 */
@Mapper
public interface ProcParseFailMapper extends BaseMapper<ProcParseFail> {
}