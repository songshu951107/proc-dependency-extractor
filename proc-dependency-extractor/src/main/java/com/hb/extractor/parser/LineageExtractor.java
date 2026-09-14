package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ConditionField;
import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageFilter;
import com.hb.extractor.parser.dto.LineageParseResult;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 血缘派生关系解析器（基于 JsqlParser AST）
 *
 * 按 SQL 块顺序提取表级派生边（source → target），
 * 支持 CREATE TEMP / INSERT SELECT / MERGE / UPDATE / DELETE，
 * 支持 JOIN ON 和 WHERE 条件提取，触发字段级映射。
 *
 * 替代原正则方案：
 * - 子查询递归提取由 JsqlParser AST 自动完成
 * - JOIN ON / WHERE 由 AST 结构化访问，不再依赖正则
 * - schema 占位符 {CDM}.table 由 SqlPlaceholderReplacer 预处理
 *
 * 调用契约与原 LineageExtractor 完全一致：
 *   extract(List<String> sqlBlocks, schemaVarMap, updateTarget) → LineageParseResult
 */
@Slf4j
@Component
public class LineageExtractor {

    @Autowired
    private JsqlSqlAnalyzer analyzer;

    @Autowired
    private DdlParser ddlParser;

    @Autowired
    private FieldMappingExtractor fieldMappingExtractor;

    @Autowired
    private com.hb.extractor.mapper.TableColumnMetaMapper tableColumnMetaMapper;

    /**
     * 解析血缘派生关系
     *
     * @param sqlBlocks    去注释后的 SQL 块列表（来自 SqlBlockExtractor.extractBlocksWithMeta().content）
     * @param schemaVarMap schema 变量→来源映射（CDM → etl.dwa_db_cdm）
     * @param updateTarget 头部声明的更新对象完整名（如 CDM.C_AGT_DEPOSIT_ACCT_INF）
     * @return LineageParseResult（edges + filters + fieldMappings）
     */
    public LineageParseResult extract(List<String> sqlBlocks,
                                      Map<String, String> schemaVarMap,
                                      String updateTarget) {
        LineageParseResult result = new LineageParseResult();
        result.setEdges(new ArrayList<>());
        result.setFilters(new ArrayList<>());
        result.setFieldMappings(new ArrayList<>());

        String updateTargetShort = extractShortName(updateTarget);

        if (sqlBlocks == null) return result;

        // 临时表列定义缓存：CREATE TEMP 块存入列定义，供同 proc 后续 INSERT SELECT 块按位置匹配。
        // 临时表无 DDL 文件，若不补全则裸列会退化为 expr_N 占位名，导致字段级血缘在临时表处断裂。
        Map<String, List<String>> tempColumnCache = new HashMap<>();
        // P12: 裸表名→全名（schema 补全）缓存，proc 级复用避免重复查库；value 可为 null（不可补全）
        Map<String, String> schemaResolveCache = new HashMap<>();
        int blockSeq = 1;
        for (String block : sqlBlocks) {
            try {
                parseBlock(block, blockSeq, schemaVarMap, updateTargetShort, result, tempColumnCache, schemaResolveCache);
            } catch (Exception e) {
                log.warn("SQL 块 {} 解析失败, 跳过: {}", blockSeq, e.getMessage());
                result.recordFailure(blockSeq, null, e.getMessage(), block);
            }
            blockSeq++;
        }

        return result;
    }

    /**
     * P4: 解开 SELECT 的括号形式。AS (SELECT ...) / INSERT (SELECT ...) 时
     * getSelectBody() 返回 ParenthesedSelect 而非 PlainSelect, 逐层解开后返回内层
     * PlainSelect; UNION/SetOperationList 等非 PlainSelect 形式返回 null（保持原行为）。
     */
    private PlainSelect unwrapPlainSelect(Select sel) {
        if (sel == null) return null;
        for (int i = 0; i < 5 && sel != null; i++) {
            Object body = sel.getSelectBody();
            if (body instanceof PlainSelect) return (PlainSelect) body;
            if (body instanceof ParenthesedSelect) {
                sel = ((ParenthesedSelect) body).getSelect();
                continue;
            }
            return null;
        }
        return null;
    }

    /**
     * 剥离列名两侧引号（GBase 内联 DDL 常用双引号包裹列名，如 "DATA_DT"）
     */
    private String stripQuotes(String s) {
        if (s == null) return null;
        if ((s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))
                && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 解析单个 SQL 块：识别操作类型 + 目标表 + 源表 + filter + 字段映射
     *
     * @param tempColumnCache 临时表列定义缓存（key=表短名小写, value=CREATE TEMP 块内联列定义）。
     *                        CREATE TEMP 块写入；INSERT SELECT 块在 ddlParser 取不到列定义时回退读取。
     * @param schemaResolveCache P12: 裸表名→全名缓存（proc 级），避免同 proc 多块重复查元数据。
     */
    private void parseBlock(String sqlBlock, int blockSeq, Map<String, String> schemaVarMap,
                             String updateTargetShort, LineageParseResult result,
                             Map<String, List<String>> tempColumnCache,
                             Map<String, String> schemaResolveCache) {
        if (sqlBlock == null || sqlBlock.trim().isEmpty()) return;

        JsqlSqlAnalyzer.ParseOutcome outcome = analyzer.parseVerbose(sqlBlock);
        Statement stmt = outcome.statement;
        if (stmt == null) {
            result.recordFailure(blockSeq, null, outcome.failureReason, sqlBlock);
            return;
        }

        // 识别操作类型和目标表
        String operation = null;
        String targetSchema = null;
        String targetTable = null;
        PlainSelect sourceSelect = null;
        boolean targetIsTemp = false;
        List<String> inlineDdlFields = null;

        if (stmt instanceof CreateTable) {
            CreateTable ct = (CreateTable) stmt;
            if (ct.getTable() != null) {
                targetSchema = ct.getTable().getSchemaName();
                targetTable = ct.getTable().getName();
                operation = "CREATE TEMP";
                targetIsTemp = true;
                // CREATE TEMP TABLE 的内联字段定义
                // GBase 方言列名常带双引号（"DATA_DT"），剥离引号与物理表元数据列名形态对齐
                if (ct.getColumnDefinitions() != null) {
                    inlineDdlFields = new ArrayList<>();
                    for (net.sf.jsqlparser.statement.create.table.ColumnDefinition cd : ct.getColumnDefinitions()) {
                        inlineDdlFields.add(stripQuotes(cd.getColumnName()).toLowerCase());
                    }
                }
                // CTAS：CREATE TABLE AS SELECT（含 AS (SELECT ...) 括号形式, P4）
                sourceSelect = unwrapPlainSelect(ct.getSelect());
            }
        } else if (stmt instanceof Insert) {
            Insert ins = (Insert) stmt;
            if (ins.getTable() != null) {
                targetSchema = ins.getTable().getSchemaName();
                targetTable = ins.getTable().getName();
                operation = "INSERT SELECT";
                // 临时表判断：表名规则 或 同 proc CREATE TEMP 块定义过（tempColumnCache 含）
                // 后者覆盖 a_pfal_deposit_term_inf_v 这类不匹配 VT_/TMP_/_TMP 后缀的临时表
                targetIsTemp = JsqlSqlAnalyzer.isTempTableName(targetTable)
                        || tempColumnCache.containsKey(targetTable.toLowerCase());
                Select sel = ins.getSelect();
                if (sel != null) {
                    // INSERT ... (SELECT ...) 括号形式同样解开（P4 与 CTAS 同型）
                    sourceSelect = unwrapPlainSelect(sel);
                }
            }
        } else if (stmt instanceof Merge) {
            Merge m = (Merge) stmt;
            if (m.getTable() != null) {
                targetSchema = m.getTable().getSchemaName();
                targetTable = m.getTable().getName();
                operation = "MERGE";
                targetIsTemp = JsqlSqlAnalyzer.isTempTableName(targetTable);
                // MERGE 的 USING 子查询作为 source
                sourceSelect = extractMergeSourceSelect(m);
            }
        } else if (stmt instanceof Update) {
            Update upd = (Update) stmt;
            if (upd.getTable() != null) {
                targetSchema = upd.getTable().getSchemaName();
                targetTable = upd.getTable().getName();
                operation = "UPDATE FROM";
                targetIsTemp = JsqlSqlAnalyzer.isTempTableName(targetTable);
                // JsqlParser 标准 UPDATE 无 FROM，源表不提取（保持与原方案一致）
            }
        } else if (stmt instanceof Delete) {
            Delete del = (Delete) stmt;
            if (del.getTable() != null) {
                targetSchema = del.getTable().getSchemaName();
                targetTable = del.getTable().getName();
                operation = "DELETE FROM";
                targetIsTemp = JsqlSqlAnalyzer.isTempTableName(targetTable);
            }
        }

        if (targetTable == null) return;  // 非写入块跳过

        String targetTableLower = targetTable.toLowerCase();
        String targetFull = buildFullName(targetSchema, targetTable, schemaVarMap);
        // P12: 目标表裸名（无 schema 前缀）且非临时表 → 从元数据补全 schema，
        // 避免 Neo4j 中与带前缀版本分裂为两个 Table 节点（影响分析"未被引用"）
        if (targetSchema == null && !targetIsTemp) {
            String metaFull = resolveFullFromMeta(targetTableLower, schemaResolveCache);
            if (metaFull != null) {
                targetFull = metaFull;
            }
        }

        // CREATE TEMP 块：将内联列定义写入缓存，供同 proc 后续 INSERT SELECT 块按位置匹配
        if ("CREATE TEMP".equals(operation) && inlineDdlFields != null && !inlineDdlFields.isEmpty()) {
            tempColumnCache.put(targetTableLower, inlineDdlFields);
        }

        // 提取源表（递归处理子查询，深度 ≤5）
        List<JsqlSqlAnalyzer.TableRefInfo> sourceTables;
        if (sourceSelect != null) {
            sourceTables = analyzer.extractTablesRecursively(sourceSelect, 0);
        } else {
            sourceTables = new ArrayList<>();
        }

        // 提取 aliasMap（用于字段映射归属）
        Map<String, String> aliasMap = analyzer.extractAliasMap(sourceSelect);
        if ("INSERT SELECT".equals(operation)) {
            log.debug("parseBlock aliasMap: blockSeq={}, target={}, aliasMap={}", blockSeq, targetTableLower, aliasMap);
        }

        // 提取 JOIN ON 和 WHERE filter（递归下钻派生表子查询，覆盖子查询内条件）
        List<JsqlSqlAnalyzer.FilterInfo> filters = new ArrayList<>();
        if (sourceSelect != null) {
            filters.addAll(analyzer.extractFiltersRecursively(sourceSelect));
        }

        // 构造 LineageEdge（每个源表一条边，过滤掉等于 targetTable 和 updateTargetShort 的表）
        String mainTable = sourceTables.isEmpty() ? null
                : (sourceTables.get(0).tableName != null ? sourceTables.get(0).tableName : null);

        int edgeIndexBase = result.getEdges().size();
        for (JsqlSqlAnalyzer.TableRefInfo src : sourceTables) {
            if (src.tableName == null || src.isSubquery) continue;
            String srcTableLower = src.tableName.toLowerCase();
            // 过滤目标表自身
            if (srcTableLower.equals(targetTableLower)) continue;
            // 过滤头部声明的更新目标表
            if (updateTargetShort != null && updateTargetShort.equalsIgnoreCase(srcTableLower)) continue;

            LineageEdge edge = new LineageEdge();
            // P12: 源表裸名（无 schema 前缀，fullTableName=裸名小写）且非临时表 → 从元数据补全 schema，
            // 保证跨 proc 链路（下游读上游裸名写入的物理表）节点归并到同一 Table 节点
            String srcFull = src.fullTableName;
            if (src.schemaName == null && !JsqlSqlAnalyzer.isTempTableName(srcTableLower)) {
                String metaFull = resolveFullFromMeta(srcTableLower, schemaResolveCache);
                if (metaFull != null) srcFull = metaFull;
            }
            edge.setSourceTable(srcFull != null ? srcFull : srcTableLower);
            edge.setSourceTableShort(srcTableLower);
            edge.setTargetTable(targetFull);
            edge.setTargetTableShort(targetTableLower);
            edge.setTargetIsTemp(targetIsTemp);
            edge.setOperation(operation);
            edge.setSqlBlockSeq(blockSeq);
            edge.setMainTable(mainTable);
            result.getEdges().add(edge);
        }

        // 构造 LineageFilter
        for (JsqlSqlAnalyzer.FilterInfo fi : filters) {
            LineageFilter lf = new LineageFilter();
            lf.setEdgeIndex(edgeIndexBase);  // 关联到该块第一条边（落库时换算 lineage_id）
            lf.setFilterType(fi.filterType);
            lf.setFilterClause(fi.clause);
            List<String> fieldStrings = new ArrayList<>();
            if (fi.fields != null) {
                for (JsqlSqlAnalyzer.ColumnRef cf : fi.fields) {
                    if (cf.alias != null && cf.field != null) {
                        fieldStrings.add(cf.alias + "." + cf.field);
                    } else if (cf.field != null) {
                        fieldStrings.add(cf.field);
                    }
                }
            }
            lf.setFieldsInvolved(fieldStrings);
            result.getFilters().add(lf);
        }

        // 字段映射：仅 INSERT SELECT / CREATE TEMP 块
        if (("INSERT SELECT".equals(operation) || "CREATE TEMP".equals(operation)) && sourceSelect != null) {
            List<String> ddlFields = inlineDdlFields;
            if (ddlFields == null || ddlFields.isEmpty()) {
                // 从 DdlParser（数据库或文件）取字段列表
                ddlFields = ddlParser.parse(targetTable);
            }
            // 临时表无 DDL 文件时，回退到同 proc 内 CREATE TEMP 块的内联列定义（按位置匹配裸列）
            if ((ddlFields == null || ddlFields.isEmpty()) && targetIsTemp) {
                ddlFields = tempColumnCache.get(targetTableLower);
            }
            List<FieldMapping> mappings = fieldMappingExtractor.extract(
                    sqlBlock, targetTableLower, aliasMap, ddlFields, tempColumnCache);
            int edgeIdx = edgeIndexBase;
            for (FieldMapping fm : mappings) {
                fm.setEdgeIndex(edgeIdx);
                result.getFieldMappings().add(fm);
            }
            // CTAS 形式 CREATE TEMP（AS SELECT, 无内联列定义）: 派生列（SELECT 项别名/列名）也写入缓存,
            // 供同 proc 后续块多表 JOIN 裸列唯一匹配归属（否则临时表列不进候选缓存, 裸列无法归属）
            if ("CREATE TEMP".equals(operation) && targetIsTemp
                    && !tempColumnCache.containsKey(targetTableLower)) {
                List<String> derivedCols = new ArrayList<>();
                for (FieldMapping fm : mappings) {
                    if (fm.getTargetField() != null) derivedCols.add(fm.getTargetField());
                }
                if (!derivedCols.isEmpty()) {
                    tempColumnCache.put(targetTableLower, derivedCols);
                }
            }
        }

        // 条件字段（行血缘）：独立于字段映射提取，归属到本块对应源表的 edge
        if (("INSERT SELECT".equals(operation) || "CREATE TEMP".equals(operation)) && sourceSelect != null
                && result.getEdges().size() > edgeIndexBase) {
            List<ConditionField> condFields =
                    fieldMappingExtractor.extractConditionFields(sourceSelect, aliasMap);
            for (ConditionField cf : condFields) {
                LineageEdge owner = null;
                // 优先归属 sourceTableShort 命中的边
                if (cf.getSourceTableShort() != null) {
                    for (int i = edgeIndexBase; i < result.getEdges().size(); i++) {
                        LineageEdge e = result.getEdges().get(i);
                        if (cf.getSourceTableShort().equals(e.getSourceTableShort())) {
                            owner = e;
                            break;
                        }
                    }
                }
                // 未命中（含未归属）：挂到本块第一条边，保证 clause 仍可落库查询
                if (owner == null) owner = result.getEdges().get(edgeIndexBase);
                owner.getConditionFields().add(cf);
            }
        }
    }

    /** 提取 MERGE 的 USING 子查询（JsqlParser 4.9 API） */
    private PlainSelect extractMergeSourceSelect(Merge m) {
        try {
            // 4.9: Merge 的 USING 表/子查询统一通过 getFromItem() 获取
            FromItem fi = m.getFromItem();
            if (fi instanceof ParenthesedSelect) {
                Select inner = ((ParenthesedSelect) fi).getSelect();
                if (inner instanceof PlainSelect) return (PlainSelect) inner;
            }
        } catch (Exception e) {
            // API 差异忽略
        }
        return null;
    }

    /**
     * 构造完整表名 schema.table（与原 buildFullName 行为一致，schema 用变量名而非展开值）
     */
    private String buildFullName(String schema, String table, Map<String, String> schemaVarMap) {
        if (schema == null) return table != null ? table.toLowerCase() : null;
        return schema + "." + (table != null ? table.toLowerCase() : null);
    }

    /**
     * P12: 裸表名（无 schema 前缀）从元数据补全 schema。
     * 脚本中 INSERT INTO / FROM 引用物理表不带 schema 前缀时（如 new_pord_sum block 9），
     * 全名与带前缀版本分裂为两个 Table 节点，导致影响分析按 schema 查询"未被引用"且跨 proc 链路断裂。
     * 查 table_column_meta 中该短名的唯一 full_table_name（如 pid.a_g_ln_new_pord_sum）补全；
     * 临时表（VT_/TMP_/_TMP）或元数据无/多义记录时不补全，保持原行为。
     *
     * @param schemaCache 本次 extract 内的短名→全名缓存（避免重复查库）
     * @return 补全后的全名（SCHEMA.short，schema 大写与既有边风格一致）；无法补全返回 null
     */
    private String resolveFullFromMeta(String tableShortLower, Map<String, String> schemaCache) {
        if (tableShortLower == null || tableShortLower.isEmpty()) return null;
        if (JsqlSqlAnalyzer.isTempTableName(tableShortLower)) return null;
        if (schemaCache.containsKey(tableShortLower)) return schemaCache.get(tableShortLower);
        String resolved = null;
        try {
            java.util.List<com.hb.extractor.entity.TableColumnMeta> records =
                    tableColumnMetaMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.hb.extractor.entity.TableColumnMeta>()
                                    .eq(com.hb.extractor.entity.TableColumnMeta::getTableName, tableShortLower));
            // 元数据每列一行，按 full_table_name 去重后判唯一：
            // 唯一 → 补全；无记录或多 schema 同名（多义）→ 不补全，保持裸名
            java.util.Set<String> distinctFulls = new java.util.HashSet<>();
            if (records != null) {
                for (com.hb.extractor.entity.TableColumnMeta r : records) {
                    if (r.getFullTableName() != null) distinctFulls.add(r.getFullTableName().toLowerCase());
                }
            }
            if (distinctFulls.size() == 1) {
                String full = distinctFulls.iterator().next();
                if (full.contains(".")) {
                    resolved = full.substring(0, full.lastIndexOf('.')).toUpperCase() + "." + tableShortLower;
                }
            }
        } catch (Exception e) {
            log.debug("裸表名 schema 补全查询失败 table={}: {}", tableShortLower, e.getMessage());
        }
        schemaCache.put(tableShortLower, resolved);
        return resolved;
    }

    /** 从 "CDM.C_AGT_DEPOSIT_ACCT_INF" 提取短名 "C_AGT_DEPOSIT_ACCT_INF" */
    private String extractShortName(String fullName) {
        if (fullName == null) return null;
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }
}
