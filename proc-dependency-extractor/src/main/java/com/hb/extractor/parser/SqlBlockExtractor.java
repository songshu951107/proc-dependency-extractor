package com.hb.extractor.parser;

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
}
