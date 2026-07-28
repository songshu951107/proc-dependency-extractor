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

        // 按 /*StepN:...*\/ 注释切分（Step 标记作为分隔符被消费）
        Pattern stepPattern = Pattern.compile("/\\*Step\\d+[^*]*\\*/");
        String[] segments = stepPattern.split(sqlContent);

        for (String seg : segments) {
            if (seg == null) continue;
            // 先移除 /* ... */ 块注释（Step 标记已被 split 消费，剩余块注释可能含分号）
            String noBlock = seg.replaceAll("(?s)/\\*.*?\\*/", " ");
            // 再移除 -- 行注释（防止注释内的分号被误当作语句分隔符）
            String noComments = noBlock.replaceAll("(?m)--.*$", "");
            String trimmed = noComments.trim();
            if (!trimmed.isEmpty()) {
                // 进一步按分号切分（处理一个 Step 内多条语句）
                String[] stmts = trimmed.split(";");
                for (String stmt : stmts) {
                    String s = stmt.trim();
                    if (!s.isEmpty()) {
                        blocks.add(s);
                    }
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

        Pattern stepPattern = Pattern.compile("/\\*Step\\d+[^*]*\\*/");
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

            // 按 /*StepN:...*/ 注释切分（与 extractBlocks 一致）
            String[] segments = stepPattern.split(sqlText);
            for (String seg : segments) {
                if (seg == null) continue;
                String noBlock = seg.replaceAll("(?s)/\\*.*?\\*/", " ");
                String noComments = noBlock.replaceAll("(?m)--.*$", "");
                String trimmed = noComments.trim();
                if (!trimmed.isEmpty()) {
                    String[] stmts = trimmed.split(";");
                    for (String stmt : stmts) {
                        String s = stmt.trim();
                        if (!s.isEmpty()) {
                            blockSeq++;
                            SqlBlockInfoDTO dto = new SqlBlockInfoDTO();
                            dto.setBlockSeq(blockSeq);
                            dto.setContent(s);
                            // 在 seg 中定位该语句，推算原文偏移
                            int segOffset = seg.indexOf(s);
                            if (segOffset < 0) segOffset = 0;
                            int originalOffset = sqlTextStart + segOffset;
                            int endOffset = Math.min(originalOffset + Math.max(1, s.length()) - 1, content.length());
                            dto.setStartLine(countLinesToOffset(content, originalOffset));
                            dto.setEndLine(countLinesToOffset(content, endOffset));
                            result.add(dto);
                        }
                    }
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
}
