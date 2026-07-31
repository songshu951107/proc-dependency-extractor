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
    /** 按深度分组：key=depth(1,2,3...), value=该层受影响表列表 */
    private java.util.Map<Integer, List<ImpactedTable>> impactedByDepth;
    /** 传播链路描述：["SOURCE → TABLE_A", "TABLE_A → TABLE_C"] */
    private List<String> propagationChains;

    @Data
    public static class NodeVO {
        private String id;
        private String label;
        private String type;           // SOURCE / TEMP / TARGET
        private String nodeLevel;      // TABLE / FIELD
        private String tableFullName;
        private Integer depth;
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
    }

    @Data
    public static class ImpactStats {
        private Integer affectedProcCount;
        private Integer affectedTableCount;
        private Integer affectedFieldCount;
        private Integer maxDepthReached;
        private List<String> affectedProcNames;
    }

    @Data
    public static class ConditionClause {
        private String clause;
        private List<String> fields;
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
