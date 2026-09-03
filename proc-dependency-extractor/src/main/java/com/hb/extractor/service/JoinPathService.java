package com.hb.extractor.service;

import com.hb.extractor.dto.nl2sql.JoinHint;
import java.util.List;

public interface JoinPathService {
    /** 候选表两两间查找血缘关联路径（直接边 + 1-2 跳共同上游） */
    List<JoinHint> findJoinPaths(List<String> tableFullNames);
}