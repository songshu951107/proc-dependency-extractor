package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ProcMetadata;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 表名提取器
 * 从 SQL 文本中提取临时表和依赖表
 *
 * 特性:
 * 1. 识别 CREATE TEMPORARY TABLE ... VT_xxx 临时表
 * 2. 识别 FROM/JOIN/INSERT INTO/DELETE FROM/UPDATE/MERGE INTO 后的表引用
 * 3. 支持 {CDM}.TABLE / CDM.TABLE / cdm.table 等多种 schema 格式
 * 4. 过滤 SQL 关键字、字符串字面量、子查询、函数调用
 * 5. 临时表不计入依赖表,更新目标表不计入依赖表
 */
@Component
public class SqlTableExtractor {

    /** 临时表识别: CREATE TEMPORARY TABLE [schema.]任意表名
     *  只要通过 CREATE TEMPORARY TABLE 方式建表即视为临时表，不限命名规则 */
    private static final Pattern TEMP_TABLE_PATTERN = Pattern.compile(
            "(?i)CREATE\\s+TEMPORARY\\s+TABLE\\s+" +
            "(\\{?(\\w+)\\}?\\.)?" +       // group1=完整schema段, group2=schema变量名
            "(\\w+)"                        // group3=表名(任意合法表名)
    );

    /**
     * 表引用识别
     * group1=操作类型, group2=完整schema段, group3=schema变量名, group4=表名
     */
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?i)\\b(INSERT\\s+INTO|DELETE\\s+FROM|MERGE\\s+INTO|UPDATE|FROM|JOIN)\\s+" +
            "(?!\\()" +                     // 排除子查询 FROM (
            "(\\{?(\\w+)\\}?\\.)?" +        // 可选 schema: {CDM}. 或 CDM.
            "([A-Za-z][A-Za-z0-9_]{2,})" +  // 表名: 字母开头,至少3个字符
            "\\b(?!\\s*[(.])"               // 后面不能是括号(函数调用)或点(列名引用)
    );

    /** SQL 关键字白名单,作为表名时需过滤(注意: Set.of 不允许重复元素) */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION",
            "ALL", "DISTINCT", "AS", "ON", "AND", "OR", "NOT", "NULL", "CASE", "WHEN",
            "THEN", "ELSE", "END", "BY", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
            "CROSS", "NATURAL", "USING", "WITH", "SET", "VALUES", "EXISTS", "DUAL",
            "BETWEEN", "LIKE", "ASC", "DESC", "JOIN", "INTO", "UPDATE", "DELETE",
            "INSERT", "MERGE", "TABLE", "TEMPORARY", "CREATE", "DROP", "ALTER",
            "TRUNCATE", "PARTITION", "OVER", "DISTRIBUTED", "COMMENT", "PRIMARY",
            "FOREIGN", "KEY", "INDEX", "VIEW", "SEQUENCE", "SCHEMA", "DATABASE",
            "UNIQUE", "CHECK", "DEFAULT", "REFERENCES", "CONSTRAINT", "COLUMN",
            "RENAME", "EXCHANGE", "ANALYZE", "OPTIMIZE", "REPAIR", "LOCK", "UNLOCK",
            "EXPLAIN", "DESCRIBE", "SHOW", "USAGE", "START", "BEGIN", "DECLARE",
            "CURSOR", "FETCH", "OPEN", "CLOSE", "RETURN", "EXCEPTION", "GREATEST",
            "LEAST", "CURRENT_DATE", "CURRENT_TIMESTAMP", "SYSDATE", "USER",
            "SESSION_USER", "SYSTEM_USER", "CONDITION", "PROCEDURE", "FUNCTION",
            "TRIGGER", "EACH", "ROW", "COMMIT", "ROLLBACK", "SAVEPOINT",
            "TRANSACTION", "PREPARE", "EXECUTE", "DEALLOCATE", "RESET", "CLUSTER",
            "VACUUM", "COPY", "GRANT", "REVOKE", "DENY", "AUDIT", "INTERSECT",
            "MINUS", "EXCEPT", "CORRESPONDING", "TOP", "OFFSET",
            "NEXT", "ROWS", "ONLY", "GROUPING", "SETS", "ROLLUP", "CUBE",
            "RECURSIVE", "MATERIALIZED", "CAST", "CONVERT", "DATE", "TIME",
            "TIMESTAMP", "INTERVAL", "ZONE", "YEAR", "MONTH", "DAY", "HOUR",
            "MINUTE", "SECOND", "FILTER", "WITHIN", "ARRAY", "AGGREGATE",
            "SEARCH", "CYCLE", "DEPTH", "BREADTH", "STORAGE", "COMPRESSION",
            "COMPRESS", "ENCRYPTION", "ENCRYPT", "DECRYPT", "CACHE", "NOCACHE",
            "LOG", "NOLOG", "MONITORING", "NOMONITORING", "PARALLEL", "NOPARALLEL",
            "REBUILD", "INVALIDATE", "NOINVALIDATE", "COMPILE",
            "BODY", "SPECIFICATION", "PACKAGE", "TYPE", "UNDER", "REPLACE",
            "FORCE", "NOFORCE", "VALIDATE", "NOVALIDATE", "DEPENDENT", "CASCADE",
            "NOCASCADE", "STRICT", "RELAXED", "DEFINER", "INVOKER", "SECURITY",
            "DETERMINISTIC", "READ", "WRITE", "ISOLATION", "LEVEL", "SERIALIZABLE",
            "REPEATABLE", "COMMITTED", "UNCOMMITTED", "SNAPSHOT", "OVERWRITE",
            "BINDING", "SCOPE", "REF", "REF_IS", "OID", "TREAT", "NORMALIZE",
            "DOCUMENT", "CONTENT", "XML", "XMLNAMESPACES",
            "XMLROOT", "XMLCONCAT", "XMLELEMENT", "XMLFOREST", "XMLPARSE",
            "XMLPI", "XMLCOMMENT", "XMLAGG", "XMLTABLE"
    );

    /**
     * 提取 SQL 中的临时表和依赖表
     */
    public void extract(String sql, ProcMetadata metadata) {
        // 1. 识别 CREATE TEMPORARY TABLE 创建的临时表
        extractTempTables(sql, metadata);

        // 2. 提取 FROM/JOIN/INTO 等表引用
        extractTableRefs(sql, metadata);
    }

    /**
     * 提取临时表
     */
    private void extractTempTables(String sql, ProcMetadata metadata) {
        Matcher m = TEMP_TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            String schema = m.group(2) != null ? m.group(2).toUpperCase() : null;
            String table = m.group(3).toUpperCase();
            String src = schema != null ? metadata.getSchemaVarMap().get(schema) : null;

            ProcMetadata.TableRef ref = new ProcMetadata.TableRef(schema, table, "CREATE TEMPORARY");
            ref.setSchemaVarSrc(src);
            ref.setTempTable(true);
            metadata.getTempTables().add(ref);
        }
    }

    /** 写入型操作关键字(目标表,不计入依赖) */
    private static final Set<String> WRITE_OPERATIONS = Set.of(
            "INSERT INTO", "DELETE FROM", "MERGE INTO", "UPDATE"
    );

    /**
     * 提取表引用
     */
    private void extractTableRefs(String sql, ProcMetadata metadata) {
        // 移除单引号字符串字面量,避免误匹配 'F_xxx' AS data_sourc_tab_name
        String cleaned = sql.replaceAll("'[^'\n]*'", "''");

        Matcher m = TABLE_REF_PATTERN.matcher(cleaned);
        while (m.find()) {
            String opType = m.group(1).toUpperCase().replaceAll("\\s+", " ");
            String schema = m.group(3) != null ? m.group(3).toUpperCase() : null;
            String table = m.group(4).toUpperCase();

            // 过滤 SQL 关键字
            if (SQL_KEYWORDS.contains(table)) {
                continue;
            }

            String src = schema != null ? metadata.getSchemaVarMap().get(schema) : null;
            ProcMetadata.TableRef ref = new ProcMetadata.TableRef(schema, table, opType);
            ref.setSchemaVarSrc(src);

            // 判断是否临时表(VT_/TMP_前缀、_TMP后缀 或 已在临时表集合中)
            if (isTempTableName(table) || containsTable(metadata.getTempTables(), ref)) {
                ref.setTempTable(true);
                metadata.getTempTables().add(ref);
                continue;
            }

            // 写入型操作(INSERT INTO/DELETE FROM/MERGE INTO/UPDATE)的目标表不计入依赖
            // 记录到 inferredUpdateTargets,用于无头部注释时回填 update_target
            if (WRITE_OPERATIONS.contains(opType)) {
                String fullKey = (schema != null ? schema + "." : "") + table;
                metadata.getInferredUpdateTargets().putIfAbsent(fullKey.toUpperCase(Locale.ROOT), ref);
                continue;
            }

            // 读取型操作(FROM/JOIN): 若该表已被识别为更新目标,则跳过
            String fullKey = (schema != null ? schema + "." : "") + table;
            if (metadata.getInferredUpdateTargets().containsKey(fullKey.toUpperCase(Locale.ROOT))) {
                continue;
            }

            // 过滤更新目标表(头部注释声明的,不计入依赖)
            String updateTargetTable = metadata.getUpdateTargetTable();
            if (updateTargetTable != null && updateTargetTable.equalsIgnoreCase(table)) {
                continue;
            }
            metadata.getDependencies().add(ref);
        }
    }

    /**
     * 判断临时表集合是否已包含某表(按 schema+tableName)
     */
    private boolean containsTable(Set<ProcMetadata.TableRef> set, ProcMetadata.TableRef ref) {
        return set.stream().anyMatch(t -> t.equals(ref));
    }

    /**
     * 统一临时表判断规则（不区分大小写）：
     * - VT_ 前缀
     * - TMP_ 前缀
     * - _TMP 后缀（含 _TMP1, _TMP2 等数字后缀）
     */
    private static boolean isTempTableName(String shortName) {
        if (shortName == null || shortName.isEmpty()) return false;
        String upper = shortName.toUpperCase();
        return upper.startsWith("VT_")
                || upper.startsWith("TMP_")
                || upper.matches(".*_TMP\\d*");
    }
}
