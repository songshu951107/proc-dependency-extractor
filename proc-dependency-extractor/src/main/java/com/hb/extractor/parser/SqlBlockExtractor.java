package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.SqlBlockInfoDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三引号 SQL 块抽取器
 * 抽取脚本中 bsql1/bsqlmapping/bsql2/bsql 等三引号字符串变量内容
 */
@Component
public class SqlBlockExtractor {

    /**
     * 匹配形如: bsql* = """ ... """
     * 非贪婪匹配三引号内容
     */
    private static final Pattern SQL_BLOCK_PATTERN = Pattern.compile(
            "(?ms)\\bbsql\\w*\\s*=\\s*\"\"\"(.*?)\"\"\""
    );

    /**
     * 连续空行折叠为单个换行（与 SqlPlaceholderReplacer 一致）。
     * JsqlParser 4.9 在 ≥2 连续空行处会误判语句结束（如 UNION 前多空行报 "UNION"），
     * 折叠后可直接解析；也避免落库块内容因空行过度分页。
     */
    private static final Pattern MULTI_NEWLINE_PATTERN =
            Pattern.compile("(?:\\r?\\n[ \\t]*){2,}");

    /**
     * 抽取所有 bsql* 三引号字符串内容并拼接
     */
    public String extract(String content) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = SQL_BLOCK_PATTERN.matcher(content);
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        // 按出现顺序拼接,与脚本执行顺序一致
        for (String block : blocks) {
            sb.append(block).append('\n');
        }
        return sb.toString();
    }

    /**
     * 按顺序切分 SQL 块，返回每个块独立的内容
     * 切分依据：先抽取三引号内的 SQL 文本，再按 /*StepN*\/ 注释起点 和 分号 切分
     *
     * @param content Python 文件原文 或 已抽取的 SQL 文本
     * @return SQL 块列表（按出现顺序）
     */
    public List<String> extractBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return blocks;
        }

        // 先抽取三引号内的 SQL 文本（兼容直接传入 SQL 文本的情况）
        String sqlContent = extract(content);
        if (sqlContent == null || sqlContent.trim().isEmpty()) {
            return blocks;
        }

        // 移除所有块注释（含 /*StepN*/ 标记）与 -- 行注释。
        // 注意：/*StepN*/ 【不再】作为语句分隔符——它们常出现在单条语句中间
        // （INSERT 列清单与 SELECT 之间、UNION 前后），按它切分会拦腰切碎完整语句。
        String noBlock = sqlContent.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noComments = noBlock.replaceAll("(?m)--.*$", "");
        String trimmed = noComments.trim();
        if (!trimmed.isEmpty()) {
            // 仅按分号切分（分号位于单引号字符串内时不作为分隔符）
            for (String s : splitStatements(trimmed)) {
                String st = MULTI_NEWLINE_PATTERN.matcher(s.trim()).replaceAll("\n");
                if (!st.isEmpty()) {
                    blocks.add(st);
                }
            }
        }

        // 如果未切分出块，整体作为一个块
        if (blocks.isEmpty() && !sqlContent.trim().isEmpty()) {
            blocks.add(sqlContent.trim());
        }

        return blocks;
    }

    /**
     * 按顺序切分 SQL 块，返回每个块的内容与行号信息（基于原始 Python 文件）。
     * 切分逻辑与 {@link #extractBlocks(String)} 一致，额外记录每个块在原文中的行号范围，
     * 用于脚本查看器高亮定位。
     *
     * @param content Python 文件原文
     * @return SQL 块信息列表（blockSeq 从 1 开始）
     */
    public List<SqlBlockInfoDTO> extractBlocksWithMeta(String content) {
        List<SqlBlockInfoDTO> result = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return result;
        }

        Matcher blockMatcher = SQL_BLOCK_PATTERN.matcher(content);
        int blockSeq = 0;

        while (blockMatcher.find()) {
            // 三引号内的 SQL 文本（不包含三引号本身）
            String sqlText = blockMatcher.group(1);
            // sqlText 在原文中的起始偏移（用于映射行号）
            int sqlTextStart = blockMatcher.start(1);
            if (sqlText == null || sqlText.trim().isEmpty()) {
                continue;
            }

            // 移除所有块注释（含 /*StepN*/ 标记）与 -- 行注释；不按 Step 标记切分
            String noBlock = sqlText.replaceAll("(?s)/\\*.*?\\*/", " ");
            String noComments = noBlock.replaceAll("(?m)--.*$", "");
            String trimmed = noComments.trim();
            if (!trimmed.isEmpty()) {
                int searchFrom = 0;
                for (String s : splitStatements(trimmed)) {
                    s = MULTI_NEWLINE_PATTERN.matcher(s.trim()).replaceAll("\n");
                    if (s.isEmpty()) continue;
                    blockSeq++;
                    SqlBlockInfoDTO dto = new SqlBlockInfoDTO();
                    dto.setBlockSeq(blockSeq);
                    dto.setContent(s);
                    // 在去注释文本中定位该语句，推算原文偏移（行号仅用于查看器高亮，近似即可）
                    int segOffset = noComments.indexOf(s, searchFrom);
                    if (segOffset < 0) segOffset = searchFrom;
                    searchFrom = segOffset + s.length();
                    int originalOffset = sqlTextStart + segOffset;
                    int endOffset = Math.min(originalOffset + Math.max(1, s.length()) - 1, content.length());
                    dto.setStartLine(countLinesToOffset(content, originalOffset));
                    dto.setEndLine(countLinesToOffset(content, endOffset));
                    result.add(dto);
                }
            }
        }

        // 兜底：原文无可识别三引号包裹但存在可切分 SQL（与 extractBlocks 行为对齐）
        if (result.isEmpty()) {
            String sqlContent = extract(content);
            if (sqlContent != null && !sqlContent.trim().isEmpty()) {
                blockSeq++;
                SqlBlockInfoDTO dto = new SqlBlockInfoDTO();
                dto.setBlockSeq(blockSeq);
                dto.setContent(sqlContent.trim());
                dto.setStartLine(1);
                dto.setEndLine(countLinesToOffset(content, content.length()));
                result.add(dto);
            }
        }

        return result;
    }

    /**
     * 统计原文中 [0, offset) 范围内的换行符数量，返回 1-based 行号。
     */
    private int countLinesToOffset(String content, int offset) {
        if (offset < 0) return 1;
        if (offset > content.length()) offset = content.length();
        int lines = 1;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 按分号切分语句，但跳过位于单引号字符串字面量内的分号
     * （如 GBase 列 COMMENT '数据类型:1;2;3' 里的分号，不能当作语句分隔符，
     *   否则 CREATE TEMPORARY TABLE 会被切碎成以注释文本开头的残块）。
     * 支持 SQL 标准 '' 转义引号 与反斜杠 \' 转义。
     */
    private List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        int len = sql.length();
        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                cur.append(c);
                if (c == '\'') {
                    // 连续两个单引号 '' 或前面是反斜杠 \' 属于转义，不闭合字符串
                    if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                    cur.append(c);
                } else if (c == ';') {
                    String s = cur.toString().trim();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) {
            result.add(last);
        }
        return result;
    }
}
