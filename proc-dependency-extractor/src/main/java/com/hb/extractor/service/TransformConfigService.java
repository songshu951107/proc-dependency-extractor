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
     * 批量导入预览：上传文件列表（顺序随意），后端按内容自动识别——
     * 含 INSERT INTO 旧表的为 F 加工脚本、INSERT INTO 新表的为 O 加工脚本、
     * 含 CREATE TABLE 的为对应侧建表 DDL；按 T 层源列交集推导 column_mapping。
     * 只返回预览结果，不落盘。
     *
     * @param files 每项 {name, content}
     * @return column_mapping + matched_count + unmatched_f + unmatched_o + recognized（识别结果）
     */
    Map<String, Object> importPreview(List<Map<String, String>> files,
                                      String oldTable, String newTable,
                                      String oldDb, String newDb);

    /** 确认落库：把预览确认后的表对合并写入配置（保留已有 join_columns/special_columns） */
    void applyTables(List<Map<String, Object>> entries);
}
