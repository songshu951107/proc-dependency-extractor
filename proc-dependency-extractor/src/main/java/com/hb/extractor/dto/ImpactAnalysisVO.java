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
}
