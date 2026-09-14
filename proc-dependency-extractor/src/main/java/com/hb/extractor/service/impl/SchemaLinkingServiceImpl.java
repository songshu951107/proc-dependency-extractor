package com.hb.extractor.service.impl;

import com.hb.extractor.config.Nl2SqlProperties;
import com.hb.extractor.dto.nl2sql.CandidateTable;
import com.hb.extractor.entity.TableColumnMeta;
import com.hb.extractor.entity.TableDdlInfo;
import com.hb.extractor.mapper.TableColumnMetaMapper;
import com.hb.extractor.mapper.TableDdlInfoMapper;
import com.hb.extractor.nl2sql.ValueDictionary;
import com.hb.extractor.service.LlmClient;
import com.hb.extractor.service.SchemaLinkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SchemaLinkingServiceImpl implements SchemaLinkingService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{1,}|\\d{2,}");

    @Autowired
    private TableColumnMetaMapper columnMetaMapper;

    @Autowired(required = false)
    private TableDdlInfoMapper tableDdlInfoMapper;

    @Autowired(required = false)
    private Neo4jClient neo4jClient;

    @Autowired
    private Nl2SqlProperties props;

    public SchemaLinkingServiceImpl() {}

    public SchemaLinkingServiceImpl(TableColumnMetaMapper mapper, Neo4jClient client, Nl2SqlProperties props) {
        this.columnMetaMapper = mapper;
        this.neo4jClient = client;
        this.props = props;
    }

    @Override
    public List<CandidateTable> link(String question) {
        if (question == null || question.isBlank()) return Collections.emptyList();
        List<String> grams = chineseBigrams(question);
        List<String> tokens = englishTokens(question);

        // 路 1：元数据匹配
        Map<String, CandidateTable> byTable = new LinkedHashMap<>();
        List<TableColumnMeta> hits = (grams.isEmpty() && tokens.isEmpty())
                ? Collections.emptyList()
                : columnMetaMapper.selectByCommentOrColumn(grams, tokens);
        for (TableColumnMeta c : hits) {
            if (c.getFullTableName() == null) continue;
            // 命中判定：注释命中 3 > 字段名命中 2；两者均未命中则跳过该列，避免无关系入库
            boolean commentHit = grams.stream().anyMatch(g -> c.getColumnComment() != null && c.getColumnComment().contains(g));
            boolean nameHit = tokens.stream().anyMatch(t -> c.getColumnName() != null && c.getColumnName().toLowerCase().contains(t));
            if (!commentHit && !nameHit) continue;
            CandidateTable ct = byTable.computeIfAbsent(c.getFullTableName(), k -> {
                CandidateTable t = new CandidateTable();
                t.setFullTableName(c.getFullTableName());
                t.setSchemaName(c.getSchemaName());
                t.setFields(new ArrayList<>());
                t.setScore(0);
                return t;
            });
            CandidateTable.FieldBrief fb = new CandidateTable.FieldBrief();
            fb.setColumnName(c.getColumnName());
            fb.setColumnType(c.getColumnType());
            fb.setColumnComment(c.getColumnComment());
            ct.getFields().add(fb);
            if (commentHit) { ct.setScore(ct.getScore() + 3); ct.setSource("COMMENT"); }
            else if (nameHit) { ct.setScore(ct.getScore() + 2); ct.setSource("COLUMN_NAME"); }
        }

        // 路 2：血缘反查（Neo4j 可用且路 1 命中不足时）
        if (neo4jClient != null && byTable.size() < props.getMaxCandidateTables()) {
            for (String token : tokens) {
                Collection<String> hosts = neo4jClient.query(
                        "MATCH (c:Column {name: $n})-[:BELONGS_TO]->(t:Table) " +
                        "RETURN DISTINCT t.name AS name LIMIT 5")
                        .bind(token.toUpperCase()).to("n")
                        .fetchAs(String.class).mappedBy((type, record) -> record.get("name").asString())
                        .all();
                for (String host : hosts) {
                    CandidateTable ct = byTable.computeIfAbsent(host, k -> {
                        CandidateTable t = new CandidateTable();
                        t.setFullTableName(host);
                        t.setFields(new ArrayList<>());
                        t.setScore(0); // 基线 0，每个 token 命中 +2，单 token 恰为 2 分
                        t.setSource("LINEAGE");
                        return t;
                    });
                    // 仅在血缘来源上累加，避免覆盖已由 metadata 命中的表得分
                    if ("LINEAGE".equals(ct.getSource())) ct.setScore(ct.getScore() + 2);
                }
                if (byTable.size() >= props.getMaxCandidateTables()) break;
            }
        }

        // 业务词加权：问题含业务专有词（如"信用卡"）时，对表名命中目标关键词的候选表加分，优先选对业务表
        applyBusinessTermWeights(question, byTable);

        // 裁剪：排序（得分降序）→ 截断表数 → 补全血缘表字段 → 截断每表字段（数据日期字段置顶保留）
        return byTable.values().stream()
                .sorted(Comparator.comparingDouble(CandidateTable::getScore).reversed())
                .limit(props.getMaxCandidateTables())
                .peek(ct -> {
                    backfillFields(ct); // 血缘召回的表仅含表名，补全字段
                    promoteDateFields(ct.getFields());
                    if (ct.getFields().size() > props.getMaxFieldsPerTable()) {
                        ct.setFields(new ArrayList<>(ct.getFields().subList(0, props.getMaxFieldsPerTable())));
                    }
                })
                .collect(Collectors.toList());
    }

    /** 业务词加权 + 补充召回：问题命中业务专有词时，按权威表白名单把对应业务账户表（全量字段）拉进候选并加权。
     *  解决"信用卡"等业务词在字段注释中并不存在、且字段被裁剪导致关键字段缺失，业务账户表被误判的问题。 */
    private void applyBusinessTermWeights(String question, Map<String, CandidateTable> byTable) {
        Map<String, List<String>> tables = ValueDictionary.businessTermTables();
        if (tables == null || tables.isEmpty()) return;
        String q = question == null ? "" : question.toLowerCase();
        for (Map.Entry<String, List<String>> e : tables.entrySet()) {
            if (!q.contains(e.getKey().toLowerCase())) continue;
            for (String fullName : e.getValue()) {
                if (fullName == null || fullName.isBlank()) continue;
                CandidateTable ct = byTable.computeIfAbsent(fullName.toLowerCase(), k -> {
                    CandidateTable x = new CandidateTable();
                    x.setFullTableName(fullName.toLowerCase());
                    int dot = fullName.indexOf('.');
                    x.setSchemaName(dot > 0 ? fullName.substring(0, dot) : "");
                    x.setFields(new ArrayList<>());
                    x.setScore(0);
                    x.setSource("BUSINESS_TERM");
                    return x;
                });
                ct.setScore(ct.getScore() + 15); // 权威业务表强优先
            }
        }
    }

    /** 为字段缺失的候选表（如血缘召回）补全字段：数据日期字段优先，再按列序补足 */
    private void backfillFields(CandidateTable ct) {
        if (ct.getFields() == null || !ct.getFields().isEmpty()) return;
        List<TableColumnMeta> cols = columnMetaMapper.selectColumnsByFullTable(ct.getFullTableName());
        if (cols == null || cols.isEmpty()) return;
        List<CandidateTable.FieldBrief> fields = new ArrayList<>();
        for (TableColumnMeta c : cols) {
            CandidateTable.FieldBrief fb = new CandidateTable.FieldBrief();
            fb.setColumnName(c.getColumnName());
            fb.setColumnType(c.getColumnType());
            fb.setColumnComment(c.getColumnComment());
            fields.add(fb);
        }
        ct.setFields(fields);
    }

    /** 把数据日期（分区）字段移到列表头部，确保字段裁剪后仍保留，供 LLM 生成 SQL 时卡数据日期 */
    private void promoteDateFields(List<CandidateTable.FieldBrief> fields) {
        List<CandidateTable.FieldBrief> dateParts = fields.stream()
                .filter(f -> f.getColumnName() != null
                        && LlmClient.DATE_PARTITION_FIELDS.contains(f.getColumnName().toLowerCase()))
                .collect(Collectors.toList());
        if (dateParts.isEmpty()) return;
        fields.removeAll(dateParts);
        fields.addAll(0, dateParts);
    }

    /** 提取中文 2-gram（用于注释 LIKE 匹配） */
    static List<String> chineseBigrams(String question) {
        List<String> grams = new ArrayList<>();
        Matcher m = Pattern.compile("[\\u4e00-\\u9fa5]{2,}").matcher(question);
        // 滑动 2-gram，控制数量（取前 8 个，避免 SQL 过长）
        while (m.find()) {
            String seg = m.group();
            for (int i = 0; i + 2 <= seg.length() && grams.size() < 8; i++) {
                String g = seg.substring(i, i + 2);
                if (!grams.contains(g)) grams.add(g);
            }
        }
        return grams;
    }

    /** 提取英文/数字 token（字段名/疑似字段名），停用词过滤 */
    static List<String> englishTokens(String question) {
        Set<String> stop = Set.of("the", "a", "of", "in", "on", "and", "or", "to", "by", "is", "what", "how");
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(question);
        while (m.find()) {
            String t = m.group().toLowerCase();
            if (!stop.contains(t) && !tokens.contains(t)) tokens.add(t);
        }
        return tokens;
    }
}