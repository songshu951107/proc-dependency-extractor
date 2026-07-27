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
}
