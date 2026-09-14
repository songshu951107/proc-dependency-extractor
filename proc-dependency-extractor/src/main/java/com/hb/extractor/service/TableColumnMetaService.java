package com.hb.extractor.service;

import com.hb.extractor.entity.TableColumnMeta;

import java.util.List;
import java.util.Map;

public interface TableColumnMetaService {

    /**
     * 导入结果
     */
    class ImportResult {
        public int tableCount;
        public int columnCount;
        public String sourceFile;
        public long elapsedMs;
        public List<String> failures;
    }

    /**
     * 从 xlsx 文件导入物理表字段元数据。
     * 幂等：导入前清空旧数据，重新写入。
     */
    ImportResult importFromExcel(String xlsxPath);

    /**
     * 按表短名查询字段列表（替代 DdlParser 从文件读取）。
     */
    List<String> getFieldNames(String tableShort);

    /**
     * 按表短名查询字段元数据
     */
    List<TableColumnMeta> getColumns(String tableShort);

    /**
     * 按表短名查询生成的 CREATE TABLE 语句
     */
    String getDdlText(String tableShort);

    /**
     * 元数据统计信息
     */
    Map<String, Object> stats();
}
