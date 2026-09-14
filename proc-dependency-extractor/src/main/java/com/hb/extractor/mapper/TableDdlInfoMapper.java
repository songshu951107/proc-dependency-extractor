package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableDdlInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TableDdlInfoMapper extends BaseMapper<TableDdlInfo> {

    /**
     * 查询所有已导入的 schema（去重、升序，小写）。
     * 用于影响分析下拉框，覆盖全部物理表元数据。
     */
    @Select("SELECT DISTINCT schema_name FROM table_ddl_info " +
            "WHERE schema_name IS NOT NULL AND schema_name <> '' " +
            "ORDER BY schema_name")
    List<String> selectDistinctSchemas();

    /**
     * 按 schema 查询全部物理表（用于影响分析下拉框）。
     * schema_name 在库中为小写，这里按小写匹配。
     */
    @Select("SELECT * FROM table_ddl_info " +
            "WHERE schema_name = #{schemaName} " +
            "ORDER BY table_name")
    List<TableDdlInfo> selectBySchema(@Param("schemaName") String schemaName);

    /**
     * NL2SQL 业务词补充召回：按完整表名关键词模糊匹配物理表清单（小写），用于把业务词对应表拉进候选。
     */
    @Select("<script>" +
            "SELECT * FROM table_ddl_info WHERE " +
            "<foreach collection='kws' item='kw' separator=' OR '>LOWER(full_table_name) LIKE CONCAT('%', #{kw}, '%')</foreach>" +
            " AND (LOWER(full_table_name) NOT LIKE '%.vt\\_%' " +
            "     AND LOWER(full_table_name) NOT LIKE '%.tmp\\_%' " +
            "     AND LOWER(full_table_name) NOT LIKE '%.\\_tmp%')" +
            " LIMIT 30" +
            "</script>")
    List<TableDdlInfo> selectByTableNameKeywords(@Param("kws") List<String> keywords);
}
