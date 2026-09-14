package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class ImpactAnalysisVO {

    private List<NodeVO> nodes;
    private List<EdgeVO> edges;
    private List<PathVO> paths;
    private ImpactStats stats;
    private Boolean truncated;
    /** 源表全名（传播树根节点） */
    private String sourceTable;
    /** 源表数据层级（用于前端节点着色） */
    private String sourceLayer;
    /** 源表是否存在于血缘图（任何已解析脚本引用过）；false 时前端提示检查表名 */
    private Boolean sourceTableFound;
    /** 按深度分组：key=depth(1,2,3...), value=该层受影响表列表 */
    private java.util.Map<Integer, List<ImpactedTable>> impactedByDepth;
    /** 传播链路描述：["SOURCE → TABLE_A", "TABLE_A → TABLE_C"] */
    private List<String> propagationChains;
    /** 报表影响（勾选"分析报表影响"后返回）：链路上各表被哪些报表引用 */
    private List<ReportImpact> reportImpacts;
    /** 上游模式：目标列所在表的行血缘条件分区（该表行集受哪些条件控制） */
    private List<RowFilterVO> rowFilters;

    @Data
    public static class ReportImpact {
        /** 被引用的表全名（小写 schema.table） */
        private String table;
        /** 该表在血缘链路上的深度（源表为 0） */
        private Integer depth;
        /** 引用该表的报表列表 */
        private List<ReportRef> reports;
    }

    @Data
    public static class ReportRef {
        /** 报表文件名 */
        private String file;
        /** 报表名称 */
        private String name;
        /** 数据集名 */
        private String datasetName;
        /** 数据库连接 */
        private String connection;
    }

    @Data
    public static class NodeVO {
        private String id;
        private String label;
        private String type;           // SOURCE / TEMP / TARGET
        private String nodeLevel;      // TABLE / FIELD / TRANSFORM
        /** 数据层级 FDM/ODM/CDM/TDM/PID（用于前端节点着色） */
        private String layer;
        private String tableFullName;
        private Integer depth;
        private Boolean isTemp;        // 是否临时表（用于统计时排除）
        /** Transform 节点专用:表达式原文 */
        private String expression;
        /** Transform 节点专用:logic_type */
        private String logicType;
        /** Transform 节点专用:SQL 提示 */
        private String sqlHint;
        /** Transform 节点专用:所属存储过程名 */
        private String procName;
        /** Transform 节点专用:关联脚本文件 */
        private String scriptFile;
    }

    @Data
    public static class EdgeVO {
        private String source;
        private String target;
        private String operation;
        private Integer sqlBlockSeq;
        private String mainTable;
        private List<ConditionClause> joinConditions;
        private List<ConditionClause> whereConditions;
        private String sourceExpr;
        private String sourceAlias;
        private String procName;
        /** 边类型: INPUT_TO / OUTPUT_TO / READS */
        private String edgeType;
        /** INPUT_TO 的角色: value / condition */
        private String role;
        /** OUTPUT_TO 边的表达式(冗余自 Transform 节点) */
        private String expression;
        /** 影响通道: VALUE(值派生) / ROW(行过滤) */
        private String impactKind;
        /** FILTERS 边的条件原文 */
        private String clause;
    }

    @Data
    public static class ImpactStats {
        private Integer affectedProcCount;
        private Integer affectedTableCount;
        private Integer affectedFieldCount;
        private Integer maxDepthReached;
        private List<String> affectedProcNames;
        /** 影响报表数量（勾选"分析报表影响"后返回，按报表文件去重） */
        private Integer affectedReportCount;
    }

    @Data
    public static class ConditionClause {
        private String clause;
        private List<String> fields;
    }

    @Data
    public static class RowFilterVO {
        /** 条件字段 col_key（schema.table.field） */
        private String fieldColKey;
        /** 条件字段名 */
        private String fieldName;
        /** join_condition / where_condition */
        private String role;
        /** 条件原文 */
        private String clause;
        /** 所属存储过程 */
        private String procName;
        /** SQL块序号 */
        private Integer sqlBlockSeq;
    }

    @Data
    public static class PathVO {
        private List<PathNode> nodes;
        private Integer depth;
    }

    @Data
    public static class PathNode {
        private String id;
        private String label;
    }

    @Data
    public static class ImpactedTable {
        /** 表全名 schema.table（小写） */
        private String name;
        /** 表短名（去 schema） */
        private String shortName;
        /** 数据层级 FDM/ODM/CDM/TDM/PID */
        private String layer;
        /** 是否临时表 */
        private boolean temp;
        /** 影响深度 1,2,3... */
        private int depth;
        /** 直接上游来源表（impactSource） */
        private String impactSource;
        /** 涉及的脚本文件名（用于代码片段查看） */
        private String scriptFile;
        /** 该表全部字段列表 */
        private java.util.List<String> columns;
        /** 字段级分析时匹配到的受影响字段 */
        private java.util.List<String> matchedColumns;
    }
}
