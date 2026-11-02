package com.hb.extractor.service;

import java.util.List;
import java.util.Map;

/**
 * 映射配置（transform_config.json）管理：表对 CRUD、批量导入推导、确认落库。
 * 引擎每次转换重新读取该文件，写回即生效。
 */
public interface TransformConfigService {

    /** 全量表对配置：{ "tables": { F表名: {...} } } */
    Map<String, Object> loadAll();

    /** 新增/更新一个表对（old_table 为主键；column_mapping/join_columns 等可选） */
    void saveTable(Map<String, Object> entry);

    /** 删除一个表对 */
    void deleteTable(String oldTable);

    /**
     * 批量导入预览（全自动，无需用户填表名）：上传文件列表（顺序随意），后端按内容识别——
     * INSERT/CREATE 目标表名以 F_ 开头为 F 层、O_ 开头为 O 层；
     * 多表对按共同 T 层源列交集最大贪心配对；库名从 INSERT INTO {SCHEMA}.xxx 提取。
     * 只返回预览结果，不落盘。
     *
     * @param files 每项 {name, content}（Excel 已在控制器层转为合成 DDL）
     * @return previews: [ {old_table, new_table, old_db, new_db, column_mapping,
     *                     matched_count, unmatched_f, unmatched_o, warnings} ] + recognized 全部识别明细
     */
    Map<String, Object> importPreviewAuto(List<Map<String, String>> files);

    /** 确认落库：把预览确认后的表对合并写入配置（保留已有 join_columns/special_columns） */
    void applyTables(List<Map<String, Object>> entries);
}
