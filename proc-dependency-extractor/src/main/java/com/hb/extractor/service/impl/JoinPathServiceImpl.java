package com.hb.extractor.service.impl;

import com.hb.extractor.dto.nl2sql.JoinHint;
import com.hb.extractor.service.JoinPathService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JoinPathServiceImpl implements JoinPathService {

    @Autowired(required = false)
    private Neo4jClient neo4jClient;

    public JoinPathServiceImpl() {}

    public JoinPathServiceImpl(Neo4jClient client) {
        this.neo4jClient = client;
    }

    @Override
    public List<JoinHint> findJoinPaths(List<String> tables) {
        if (tables == null || tables.size() < 2) return Collections.emptyList();
        List<JoinHint> hints = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            for (int j = i + 1; j < tables.size(); j++) {
                String a = tables.get(i);
                String b = tables.get(j);
                // 1. 直接血缘边（同一 proc 中 A→B 或 B→A 的表级血缘）
                collectDirectEdge(a, b, hints);
                // 2. 1-2 跳共同上游
                collectCommonUpstream(a, b, hints);
            }
        }
        return hints;
    }

    private void collectDirectEdge(String a, String b, List<JoinHint> out) {
        String cypher =
            "MATCH (ta:Table {name: $a}), (tb:Table {name: $b}) " +
            "MATCH (ta)-[r1:READS|PRODUCES|CONSUMES*1..2]-(mid:Script)-[r2:READS|PRODUCES|CONSUMES*1..2]-(tb) " +
            "WHERE mid.file IS NOT NULL " +
            "RETURN mid.file AS proc LIMIT 3";
        Collection<Rec> recs = query(cypher, Map.of("a", a, "b", b));
        for (Rec r : recs) {
            JoinHint h = new JoinHint();
            h.setTableA(a); h.setTableB(b);
            h.setRelation("DIRECT_EDGE");
            h.setProcName(r.proc);
            out.add(h);
        }
    }

    private void collectCommonUpstream(String a, String b, List<JoinHint> out) {
        String cypher =
            "MATCH (ca:Column)-[:BELONGS_TO]->(ta:Table {name: $a}), " +
            "      (cb:Column)-[:BELONGS_TO]->(tb:Table {name: $b}) " +
            "WHERE ca.name = cb.name " +
            "RETURN DISTINCT ca.name AS fieldName LIMIT 10";
        Collection<Rec> recs = query(cypher, Map.of("a", a, "b", b));
        if (!recs.isEmpty()) {
            JoinHint h = new JoinHint();
            h.setTableA(a); h.setTableB(b);
            h.setRelation("COMMON_COLUMN");
            h.setJoinFields(recs.stream().map(r -> r.fieldName).collect(Collectors.toList()));
            out.add(h);
        }
    }

    private Collection<Rec> query(String cypher, Map<String, Object> params) {
        if (neo4jClient == null) return Collections.emptyList();
        try {
            Neo4jClient.RunnableSpec spec = neo4jClient.query(cypher);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                spec = spec.bind(e.getValue()).to(e.getKey());
            }
            return spec.fetchAs(Rec.class)
                    .mappedBy((type, record) -> {
                        Rec r = new Rec();
                        if (record.containsKey("proc")) r.proc = record.get("proc").asString(null);
                        if (record.containsKey("fieldName")) r.fieldName = record.get("fieldName").asString();
                        return r;
                    })
                    .all();
        } catch (Exception e) {
            log.warn("JOIN 路径查询失败 a={}, b={}: {}", params.get("a"), params.get("b"), e.getMessage());
            return Collections.emptyList();
        }
    }

    private static class Rec {
        String proc;
        String fieldName;
    }
}