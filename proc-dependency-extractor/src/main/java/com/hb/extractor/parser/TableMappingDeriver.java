package com.hb.extractor.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表映射推导器：从「建表 DDL + T 层加工脚本」推导 目标表列 ← 源(T)列 的对应关系，
 * 再对 F、O 两侧按共同 T 层源列求交集，自动生成 column_mapping（F 列 → O 列）。
 *
 * 输入约定（映射配置批量导入）：
 *   - F 表 DDL / O 表 DDL：CREATE TABLE 建表语句（列名清单，作为 INSERT 无列清单时的位置对齐基准）
 *   - F 表加工脚本 / O 表加工脚本：INSERT INTO [schema.]目标表 [(列清单)] SELECT ... FROM ...
 *
 * 推导规则：
 *   1. 加工脚本解析出 T列 → 目标表列（简单列引用 alias.col / col；表达式归入未匹配）
 *   2. F 侧 (t → f_col) 与 O 侧 (t → o_col) 按 t 求交集 ⇒ column_mapping[f_col] = o_col
 */
public final class TableMappingDeriver {

    private TableMappingDeriver() { }

    /** 简单列引用：alias.col / col / `col` / "col"，允许 $ #（Oracle/GBase 风格） */
    private static final Pattern SIMPLE_COL = Pattern.compile(
            "^[`\"\\[]?[A-Za-z0-9_$#]+[`\"\\]]?(\\s*\\.\\s*[`\"\\[]?[A-Za-z0-9_$#]+[`\"\\]]?)?$");
    /** 尾部别名：expr AS name 或 expr name（仅当 expr 不是简单引用时使用） */
    private static final Pattern TAIL_ALIAS = Pattern.compile(
            "(?is)^(.*\\S)\\s+(?:AS\\s+)?[`\"\\[]?(\\*|[A-Za-z0-9_$#]+)[`\"\\]]?$");

    /** 单个目标列的解析结果 */
    public static class ColSrc {
        public final String targetCol;   // 目标表列（小写）
        public final String sourceCol;   // T 层源列（小写）；表达式为 null
        public final String expr;        // 原始 select 片段（未匹配时用于人工排查）
        public final String reason;      // 未匹配原因；匹配成功为 null

        ColSrc(String targetCol, String sourceCol, String expr, String reason) {
            this.targetCol = targetCol;
            this.sourceCol = sourceCol;
            this.expr = expr;
            this.reason = reason;
        }
    }

    /** 一条 INSERT 语句的解析结果 */
    public static class ScriptMapping {
        public final String targetTable;            // 去引号、去 schema 的表名（大写）
        public final List<ColSrc> cols = new ArrayList<>();
        ScriptMapping(String targetTable) { this.targetTable = targetTable; }
    }

    /** 一条 INSERT INTO target [(列清单)] SELECT ... 的定位 */
    private static class InsertSpan {
        final int selStart, selEnd;
        final List<String> explicitCols;   // 显式列清单（无则为 null）
        InsertSpan(int selStart, int selEnd, List<String> explicitCols) {
            this.selStart = selStart;
            this.selEnd = selEnd;
            this.explicitCols = explicitCols;
        }
    }

    /** 推导结果（预览，不落盘） */
    public static class DeriveResult {
        public final Map<String, String> columnMapping = new LinkedHashMap<>(); // f_col -> o_col
        public final List<Map<String, Object>> unmatchedF = new ArrayList<>();
        public final List<Map<String, Object>> unmatchedO = new ArrayList<>();
        public final int matchedCount;
        DeriveResult(Map<String, String> cm, List<Map<String, Object>> uf,
                     List<Map<String, Object>> uo, int matched) {
            this.columnMapping.putAll(cm);
            this.unmatchedF.addAll(uf);
            this.unmatchedO.addAll(uo);
            this.matchedCount = matched;
        }
    }

    // ------------------------------------------------------------------ DDL

    /** 解析建表 DDL，返回列名清单（小写、DDL 顺序）。忽略约束行；解析不出返回空列表 */
    public static List<String> parseDdlColumns(String ddl) {
        List<String> cols = new ArrayList<>();
        String body = ddlBody(ddl);
        if (body == null) return cols;
        for (String part : splitTopLevel(body, ',')) {
            String trimmed = stripComments(part).trim();
            if (trimmed.isEmpty()) continue;
            String head = firstToken(trimmed).toUpperCase(Locale.ROOT);
            if (head.equals("PRIMARY") || head.equals("CONSTRAINT") || head.equals("UNIQUE")
                    || head.equals("KEY") || head.equals("INDEX") || head.equals("FOREIGN")
                    || head.equals("CHECK") || head.equals("PERIOD")) {
                continue;
            }
            String name = firstToken(trimmed);
            cols.add(stripQuotes(name).toLowerCase(Locale.ROOT));
        }
        return cols;
    }

    /** 提取 CREATE TABLE ... ( ... ) 的括号体（含类型中 DECIMAL(10,2) 等嵌套括号） */
    private static String ddlBody(String ddl) {
        if (ddl == null) return null;
        String s = stripComments(ddl);
        Matcher m = Pattern.compile("(?is)CREATE\\s+TABLE\\b").matcher(s);
        if (!m.find()) return null;
        int open = s.indexOf('(', m.end());
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return s.substring(open + 1, i);
            }
        }
        return null;
    }

    // -------------------------------------------------------- 加工脚本解析

    /**
     * 解析加工脚本中所有 INSERT INTO 目标表 的 SELECT 列清单。
     * @param script      加工脚本全文
     * @param targetTable 目标表名（不含 schema，忽略大小写）
     * @param ddlColumns  目标表 DDL 列清单（INSERT 无显式列清单时按此对齐；可为 null）
     */
    public static ScriptMapping parseScript(String script, String targetTable,
                                            List<String> ddlColumns) {
        ScriptMapping result = new ScriptMapping(targetTable == null ? "" : targetTable.toUpperCase(Locale.ROOT));
        if (script == null || script.isEmpty()) return result;
        List<InsertSpan> inserts = findInserts(script, result.targetTable);
        for (InsertSpan span : inserts) {
            parseOneInsert(script, span, ddlColumns, result);
        }
        return result;
    }

    /** 找出所有 INSERT INTO <target> 语句：定位 SELECT 列表区间与显式列清单 */
    private static List<InsertSpan> findInserts(String script, String targetUpper) {
        List<InsertSpan> spans = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+([`\"\\[]?[A-Za-z0-9_$#.{}]+[`\"\\]]?)")
                .matcher(script);
        while (m.find()) {
            String table = stripQuotes(m.group(1));
            int dot = table.lastIndexOf('.');
            String shortName = (dot >= 0 ? table.substring(dot + 1) : table)
                    .toUpperCase(Locale.ROOT).replace("{", "").replace("}", "");
            if (!shortName.equals(targetUpper)) continue;
            int cursor = m.end();
            // 可选的 (列清单)
            List<String> explicitCols = null;
            int paren = nextMeaningful(script, cursor);
            if (paren >= 0 && script.charAt(paren) == '(') {
                int close = matchParen(script, paren);
                if (close < 0) continue;
                explicitCols = new ArrayList<>();
                for (String c : splitTopLevel(script.substring(paren + 1, close), ',')) {
                    if (!c.trim().isEmpty()) explicitCols.add(stripQuotes(c.trim()));
                }
                cursor = close + 1;
            }
            int sel = nextMeaningful(script, cursor);
            if (sel < 0) continue;
            if (!script.regionMatches(true, sel, "SELECT", 0, 6)) continue;
            int from = topKeyword(script, sel + 6, "FROM");
            if (from < 0) continue;
            spans.add(new InsertSpan(sel + 6, from, explicitCols));
        }
        return spans;
    }

    private static void parseOneInsert(String script, InsertSpan span,
                                       List<String> ddlColumns, ScriptMapping out) {
        String selectList = script.substring(span.selStart, span.selEnd);
        List<String> items = splitTopLevel(selectList, ',');
        List<String> explicitCols = span.explicitCols;
        for (int i = 0; i < items.size(); i++) {
            String raw = items.get(i).trim();
            if (raw.isEmpty()) continue;
            String target = null;
            if (explicitCols != null && i < explicitCols.size()) {
                target = stripQuotes(explicitCols.get(i)).toLowerCase(Locale.ROOT);
            } else if (explicitCols == null && ddlColumns != null && i < ddlColumns.size()) {
                target = ddlColumns.get(i);
            }
            // 尾部别名兜底：SELECT expr AS f_col —— 目标列可从别名取
            String src = null;
            String reason = null;
            if ("*".equals(raw)) {
                reason = "SELECT * 无法定位列";
            } else if (SIMPLE_COL.matcher(raw).matches()) {
                src = stripQuotes(lastSegment(raw)).toLowerCase(Locale.ROOT);
            } else {
                Matcher alias = TAIL_ALIAS.matcher(raw);
                if (alias.matches() && !"AS".equalsIgnoreCase(alias.group(2))) {
                    String aliasName = alias.group(2).toLowerCase(Locale.ROOT);
                    if (target == null) target = aliasName;
                    reason = "表达式列（别名 " + aliasName + "）无法自动映射";
                } else if (target == null) {
                    reason = "表达式列且无别名";
                } else {
                    reason = "表达式列无法自动映射";
                }
            }
            if (target == null) {
                target = "?expr" + (i + 1);   // 无处安放的表达式：以占位名列出，人工确认
                reason = reason == null ? "无列清单/DDL 对齐基准" : reason;
            }
            out.cols.add(new ColSrc(target, src, raw, reason));
        }
    }

    /**
     * F、O 两侧按共同 T 层源列求交集，生成 column_mapping（F 列 → O 列）与两侧未匹配清单。
     */
    public static DeriveResult derive(ScriptMapping fSide, ScriptMapping oSide) {
        Map<String, String> tToF = new LinkedHashMap<>();    // t_col -> f_col
        Map<String, String> tToO = new LinkedHashMap<>();    // t_col -> o_col
        Map<String, Map<String, Object>> fUnmatched = new LinkedHashMap<>();
        Map<String, Map<String, Object>> oUnmatched = new LinkedHashMap<>();

        collect(fSide, tToF, fUnmatched);
        collect(oSide, tToO, oUnmatched);

        Map<String, String> cm = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : tToF.entrySet()) {
            String t = e.getKey();
            String o = tToO.get(t);
            if (o != null) cm.put(e.getValue(), o);
        }
        List<Map<String, Object>> uf = new ArrayList<>(fUnmatched.values());
        List<Map<String, Object>> uo = new ArrayList<>(oUnmatched.values());
        // 一侧已匹配、另一侧未匹配的列也列入未匹配清单，便于人工补
        for (Map.Entry<String, String> e : tToF.entrySet()) {
            if (!tToO.containsKey(e.getKey())) {
                uf.add(unmatched(e.getValue(), null, "T 层源列在 O 脚本中未匹配: " + e.getKey()));
            }
        }
        for (Map.Entry<String, String> e : tToO.entrySet()) {
            if (!tToF.containsKey(e.getKey())) {
                uo.add(unmatched(e.getValue(), null, "T 层源列在 F 脚本中未匹配: " + e.getKey()));
            }
        }
        return new DeriveResult(cm, uf, uo, cm.size());
    }

    private static void collect(ScriptMapping side, Map<String, String> tToX,
                                Map<String, Map<String, Object>> unmatched) {
        for (ColSrc c : side.cols) {
            if (c.sourceCol != null) {
                tToX.putIfAbsent(c.sourceCol, c.targetCol);
            } else {
                unmatched.putIfAbsent(c.targetCol, unmatched(c.targetCol, c.expr, c.reason));
            }
        }
    }

    private static Map<String, Object> unmatched(String targetCol, String expr, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target_col", targetCol);
        m.put("expr", expr);
        m.put("reason", reason);
        return m;
    }

    // ------------------------------------------------------------------ 工具

    /** 去 -- 行注释与块注释（不处理字符串内注释——DDL/列清单场景足够） */
    private static String stripComments(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' ) inStr = !inStr;
            if (!inStr && c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                if (i < s.length()) sb.append('\n');
                continue;
            }
            if (!inStr && c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 1;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 顶层逗号/字符切分：括号深度为 0 才切，忽略字符串字面量 */
    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inStr = !inStr;
            if (inStr) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** 下一个非空白字符下标（跳过 -- 注释） */
    private static int nextMeaningful(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                continue;
            }
            return i;
        }
        return -1;
    }

    /** 与 open 匹配的 ')' 下标；找不到返回 -1 */
    private static int matchParen(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inStr = !inStr;
            if (inStr) continue;
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 从 from 开始找首个顶层（括号深度 0）出现的 SQL 关键字 word，返回其起始下标 */
    private static int topKeyword(String s, int from, String word) {
        int depth = 0;
        boolean inStr = false;
        int n = word.length();
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                i--;
                continue;
            }
            if (c == '\'') {
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth != 0 || !Character.isLetter(c) && c != '_') continue;
            if (i + n <= s.length() && s.regionMatches(true, i, word, 0, n)) {
                boolean wordBefore = i > from
                        && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '_');
                boolean wordAfter = i + n < s.length()
                        && (Character.isLetterOrDigit(s.charAt(i + n)) || s.charAt(i + n) == '_');
                if (!wordBefore && !wordAfter) return i;
            }
        }
        return -1;
    }

    private static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static String lastSegment(String ref) {
        int dot = ref.lastIndexOf('.');
        return dot >= 0 ? ref.substring(dot + 1) : ref;
    }

    private static String stripQuotes(String id) {
        return id.replaceAll("^[`\"\\[]+", "").replaceAll("[`\"\\]]+$", "");
    }
}
