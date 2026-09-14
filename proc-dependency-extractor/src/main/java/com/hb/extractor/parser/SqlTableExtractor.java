package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ProcMetadata;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
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
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.schema.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL 表名提取器（基于 JsqlParser AST）
 *
 * 从 SQL 文本提取临时表/依赖表/写入目标，mutate 写入 ProcMetadata：
 * - CREATE TEMPORARY TABLE → tempTables
 * - INSERT INTO/DELETE FROM/MERGE INTO/UPDATE 目标表 → inferredUpdateTargets（不计依赖）
 * - FROM/JOIN/USING 后的表 → dependencies（去重，去临时表，去更新目标表）
 *
 * 替代原正则方案，提升对子查询、CTE、UNION 等复杂语法的解析能力。
 */
@Slf4j
@Component
public class SqlTableExtractor {

    @Autowired
    private SqlPlaceholderReplacer placeholderReplacer;

    /**
     * 提取 SQL 中的临时表和依赖表（mutate metadata）
     * @param sql 拼接后的 SQL 文本（可能含多语句，由 SqlBlockExtractor.extract 拼接）
     */
    public void extract(String sql, ProcMetadata metadata) {
        if (sql == null || sql.trim().isEmpty()) return;
        String processed = placeholderReplacer.preprocess(sql);

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(processed);
        } catch (Exception e) {
            log.warn("SqlTableExtractor JsqlParser 解析失败, 跳过: {}", e.getMessage());
            return;
        }
        if (statements == null) return;

        for (Statement stmt : statements.getStatements()) {
            try {
                processStatement(stmt, metadata);
            } catch (Exception e) {
                log.debug("处理 statement 失败: {}", e.getMessage());
            }
        }
    }

    private void processStatement(Statement stmt, ProcMetadata metadata) {
        if (stmt instanceof CreateTable) {
            processCreateTable((CreateTable) stmt, metadata);
        } else if (stmt instanceof Insert) {
            processInsert((Insert) stmt, metadata);
        } else if (stmt instanceof Update) {
            processUpdate((Update) stmt, metadata);
        } else if (stmt instanceof Delete) {
            processDelete((Delete) stmt, metadata);
        } else if (stmt instanceof Merge) {
            processMerge((Merge) stmt, metadata);
        } else if (stmt instanceof Select) {
            processSelect((Select) stmt, metadata);
        }
        // 其他类型（DROP/ALTER/TRUNCATE/ALTER 等）跳过
    }

    /** CREATE TEMPORARY TABLE → tempTables */
    private void processCreateTable(CreateTable ct, ProcMetadata metadata) {
        Table t = ct.getTable();
        if (t == null) return;
        // 仅 TEMPORARY 临时表入 tempTables（CREATE TEMPORARY 已在 preprocess 替换）
        // JsqlParser 4.6 CreateTable 用 select 可能为 CTAS，此处仅处理临时表
        // 通过表名命名规则或 TEMPORARY 关键字判定
        boolean isTemp = isTempTableCreate(ct) || JsqlSqlAnalyzer.isTempTableName(t.getName());
        if (!isTemp) return;

        ProcMetadata.TableRef ref = buildRef(t, "CREATE TEMPORARY", metadata);
        ref.setTempTable(true);
        metadata.getTempTables().add(ref);
    }

    /** JsqlParser 4.6 没有直接标记 TEMPORARY 的属性，用 toString 包含 TEMPORARY 判定 */
    private boolean isTempTableCreate(CreateTable ct) {
        return ct.toString().toUpperCase().contains("TEMPORARY");
    }

    /** INSERT INTO 目标 → inferredUpdateTargets；SELECT 部分源表 → dependencies */
    private void processInsert(Insert ins, ProcMetadata metadata) {
        // 目标表
        if (ins.getTable() != null) {
            ProcMetadata.TableRef ref = buildRef(ins.getTable(), "INSERT INTO", metadata);
            addInferredUpdateTarget(ref, metadata);
        }
        // SELECT 部分源表
        Select select = ins.getSelect();
        if (select != null && select.getSelectBody() != null) {
            collectSourceTables(select.getSelectBody(), metadata);
        }
    }

    /** UPDATE 目标 → inferredUpdateTargets；JsqlParser 标准 UPDATE 无 FROM，源表不处理 */
    private void processUpdate(Update upd, ProcMetadata metadata) {
        Table t = upd.getTable();
        if (t != null) {
            ProcMetadata.TableRef ref = buildRef(t, "UPDATE", metadata);
            addInferredUpdateTarget(ref, metadata);
        }
        // UPDATE 的 WHERE 子查询中可能有源表，简化处理：暂不提取
    }

    /** DELETE FROM 目标 → inferredUpdateTargets */
    private void processDelete(Delete del, ProcMetadata metadata) {
        if (del.getTable() != null) {
            ProcMetadata.TableRef ref = buildRef(del.getTable(), "DELETE FROM", metadata);
            addInferredUpdateTarget(ref, metadata);
        }
        // DELETE 的 WHERE 子查询中的表暂不作为依赖
    }

    /** MERGE INTO 目标 → inferredUpdateTargets；USING 表 → dependencies */
    private void processMerge(Merge m, ProcMetadata metadata) {
        if (m.getTable() != null) {
            ProcMetadata.TableRef ref = buildRef(m.getTable(), "MERGE INTO", metadata);
            addInferredUpdateTarget(ref, metadata);
        }
        // USING 表（JsqlParser 4.6 API: getUsingTable 或 FromItem）
        Table usingTable = extractMergeUsingTable(m);
        if (usingTable != null) {
            addDependency(buildRef(usingTable, "USING", metadata), metadata);
        }
    }

    /** SELECT 语句的 FROM/JOIN 表 → dependencies */
    private void processSelect(Select sel, ProcMetadata metadata) {
        collectSourceTables(sel.getSelectBody(), metadata);
    }

    /** 递归从 Select 提取 FROM/JOIN 表到 dependencies */
    private void collectSourceTables(Select body, ProcMetadata metadata) {
        if (body instanceof PlainSelect) {
            PlainSelect ps = (PlainSelect) body;
            collectFromItem(ps.getFromItem(), metadata);
            if (ps.getJoins() != null) {
                for (Join j : ps.getJoins()) {
                    collectFromItem(j.getRightItem(), metadata);
                }
            }
        } else if (body instanceof ParenthesedSelect) {
            // 4.9: SubSelect 改名 ParenthesedSelect，getSelectBody() 改为 getSelect()
            collectSourceTables(((ParenthesedSelect) body).getSelect(), metadata);
        } else if (body instanceof net.sf.jsqlparser.statement.select.SetOperationList) {
            // UNION/INTERSECT/EXCEPT 多分支
            net.sf.jsqlparser.statement.select.SetOperationList setOp =
                    (net.sf.jsqlparser.statement.select.SetOperationList) body;
            if (setOp.getSelects() != null) {
                for (Select sb : setOp.getSelects()) {
                    collectSourceTables(sb, metadata);
                }
            }
        }
    }

    private void collectFromItem(FromItem fromItem, ProcMetadata metadata) {
        if (fromItem == null) return;
        if (fromItem instanceof Table) {
            addDependency(buildRef((Table) fromItem, "FROM", metadata), metadata);
        } else if (fromItem instanceof ParenthesedSelect) {
            // 4.9: SubSelect 改名 ParenthesedSelect，getSelectBody() 改为 getSelect()
            collectSourceTables(((ParenthesedSelect) fromItem).getSelect(), metadata);
        }
    }

    /** MERGE 的 USING 表提取（JsqlParser 4.9 API） */
    private Table extractMergeUsingTable(Merge m) {
        try {
            // 4.9: getUsingTable() 直接返回 Table（USING 为子查询时返回 null）
            return m.getUsingTable();
        } catch (Exception e) {
            // API 差异
        }
        return null;
    }

    /** 添加到 inferredUpdateTargets（写入目标表） */
    private void addInferredUpdateTarget(ProcMetadata.TableRef ref, ProcMetadata metadata) {
        if (ref == null || ref.getTableName() == null) return;
        String fullKey = (ref.getSchemaName() != null ? ref.getSchemaName() + "." : "") + ref.getTableName();
        metadata.getInferredUpdateTargets().putIfAbsent(fullKey.toUpperCase(Locale.ROOT), ref);
    }

    /** 添加到 dependencies（读取依赖表），过滤临时表和写入目标 */
    private void addDependency(ProcMetadata.TableRef ref, ProcMetadata metadata) {
        if (ref == null || ref.getTableName() == null) return;

        // 临时表命名规则 → tempTables
        if (JsqlSqlAnalyzer.isTempTableName(ref.getTableName())) {
            ref.setTempTable(true);
            metadata.getTempTables().add(ref);
            return;
        }
        // 已在 tempTables → 跳过依赖
        if (containsTable(metadata.getTempTables(), ref)) {
            return;
        }
        // 已被识别为写入目标 → 跳过依赖
        String fullKey = (ref.getSchemaName() != null ? ref.getSchemaName() + "." : "") + ref.getTableName();
        if (metadata.getInferredUpdateTargets().containsKey(fullKey.toUpperCase(Locale.ROOT))) {
            return;
        }
        // 等于头部声明的更新目标 → 跳过依赖
        String updateTargetTable = metadata.getUpdateTargetTable();
        if (updateTargetTable != null && updateTargetTable.equalsIgnoreCase(ref.getTableName())) {
            return;
        }
        metadata.getDependencies().add(ref);
    }

    /**
     * 构造 TableRef（schema 大写，table 大写，schemaVarSrc 从 schemaVarMap 取）
     */
    private ProcMetadata.TableRef buildRef(Table t, String opType, ProcMetadata metadata) {
        String schema = t.getSchemaName() != null ? t.getSchemaName().toUpperCase() : null;
        String table = t.getName() != null ? t.getName().toUpperCase() : null;
        String src = schema != null ? metadata.getSchemaVarMap().get(schema) : null;

        ProcMetadata.TableRef ref = new ProcMetadata.TableRef(schema, table, opType);
        ref.setSchemaVarSrc(src);
        ref.setFullTableName((schema != null ? schema + "." : "") + (table != null ? table : ""));
        return ref;
    }

    private boolean containsTable(java.util.Set<ProcMetadata.TableRef> set, ProcMetadata.TableRef ref) {
        return set.stream().anyMatch(t -> t.equals(ref));
    }

    /** 临时表命名规则：VT_/TMP_ 前缀，_TMP 后缀 */
    @SuppressWarnings("unused")
    private static boolean isTempTableName(String shortName) {
        return JsqlSqlAnalyzer.isTempTableName(shortName);
    }
}
