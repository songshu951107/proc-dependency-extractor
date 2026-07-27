package com.hb.extractor.parser;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * schema 变量解析器
 * 解析脚本中形如 CDM = etl.dwa_db_cdm 的变量赋值
 * 建立 变量名 -> 来源 的映射,用于依赖表追溯
 */
@Component
public class SchemaVarResolver {

    /**
     * 匹配形如: CDM = etl.dwa_db_cdm
     * 或带空格: FDM = etl.dwa_db_fdm
     */
    private static final Pattern SCHEMA_VAR_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s*=\\s*etl\\.(\\w+)\\s*$",
            Pattern.MULTILINE
    );

    /**
     * 解析 schema 变量映射
     * @return key=变量名(CDM) 大写, value=来源(etl.dwa_db_cdm)
     */
    public Map<String, String> resolve(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = SCHEMA_VAR_PATTERN.matcher(content);
        while (matcher.find()) {
            String varName = matcher.group(1).toUpperCase();
            String varSrc = "etl." + matcher.group(2);
            map.putIfAbsent(varName, varSrc);
        }
        return map;
    }
}
