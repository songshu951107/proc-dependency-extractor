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
     * 批量导入预览：解析 F/O 两侧 DDL 与加工脚本，按 T 层源列交集推导 column_mapping。
     * 只返回预览结果，不落盘。
     *
     * @param req 字段：oldTable/newTable（必填）、oldDb/newDb（可选）、
     *            fDdl/oDdl（可选，无显式列清单时作对齐基准）、fScript/oScript（必填）
     * @return column_mapping + matched_count + unmatched_f + unmatched_o
     */
    Map<String, Object> importPreview(Map<String, Object> req);

    /** 确认落库：把预览确认后的表对合并写入配置（保留已有 join_columns/special_columns） */
    void applyTables(List<Map<String, Object>> entries);
}
