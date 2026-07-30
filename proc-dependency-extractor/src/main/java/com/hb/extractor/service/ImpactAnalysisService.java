package com.hb.extractor.service;

import com.hb.extractor.dto.*;

import java.util.List;

public interface ImpactAnalysisService {

    List<String> getSchemas();

    List<TableOption> getTables(String schema);

    List<String> getFields(String tableFullName);

    ImpactAnalysisVO analyze(ImpactAnalysisRequest request);

    ImpactAnalysisVO expand(ExpandRequest request);

    List<ImpactAnalysisVO.PathVO> getPaths(String source, String target, Integer maxDepth);

    List<Neo4jSyncLogVO> getSyncStatus();
}
