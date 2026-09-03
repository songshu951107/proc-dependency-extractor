package com.hb.extractor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hb.extractor.entity.TableColumnMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TableColumnMetaMapper extends BaseMapper<TableColumnMeta> {

    /**
     * 按表短名（不含 schema）查询所有字段名（按字段序号升序）。
     * 用于替代 DdlParser.parse(shortName) 从文件读取。
     */
    @Select("SELECT column_name FROM table_column_meta " +
            "WHERE table_name = #{tableName} " +
            "ORDER BY ordinal_position")
    List<String> selectFieldNamesByTable(@Param("tableName") String tableName);

    /**
     * 按表短名查询字段元数据列表
     */
    @Select("SELECT * FROM table_column_meta " +
            "WHERE table_name = #{tableName} " +
            "ORDER BY ordinal_position")
    List<TableColumnMeta> selectByTable(@Param("tableName") String tableName);

    /**
     * 按完整表名(schema.table)查询字段名列表，用于影响分析字段下拉框。
     * full_table_name 在库中为小写，这里统一小写匹配。
     */
    @Select("SELECT column_name FROM table_column_meta " +
            "WHERE full_table_name = #{fullTableName} " +
            "ORDER BY ordinal_position")
    List<String> selectFieldNamesByFullTable(@Param("fullTableName") String fullTableName);

    /**
     * NL2SQL 召回：按中文 2-gram（匹配注释）或英文 token（匹配字段名）查询字段元数据。
     * @param grams  中文 2-gram 列表
     * @param tokens 英文/数字 token 列表（已小写）
     */
    @Select("<script>" +
            "SELECT * FROM table_column_meta WHERE " +
            "<if test='grams.size() > 0'>" +
            "  <foreach collection='grams' item='g' separator=' OR '>column_comment LIKE CONCAT('%', #{g}, '%')</foreach>" +
            "</if>" +
            "<if test='grams.size() > 0 and tokens.size() > 0'> OR </if>" +
            "<if test='tokens.size() > 0'>" +
            "  <foreach collection='tokens' item='t' separator=' OR '>LOWER(column_name) LIKE CONCAT('%', #{t}, '%')</foreach>" +
            "</if>" +
            " LIMIT 500" +
            "</script>")
    List<TableColumnMeta> selectByCommentOrColumn(@Param("grams") List<String> grams,
                                                  @Param("tokens") List<String> tokens);
}
