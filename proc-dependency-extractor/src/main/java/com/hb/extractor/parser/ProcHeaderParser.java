package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ProcMetadata;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 头部注释元数据提取器
 * 提取脚本头部的 #脚本名称、#功能说明、#更新对象 等信息
 */
@Component
public class ProcHeaderParser {

    private static final Pattern PROC_NAME_PATTERN =
            Pattern.compile("#\\s*脚本名称\\s*[:：]\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private static final Pattern PROC_DESC_PATTERN =
            Pattern.compile("#\\s*功能说明\\s*[:：]\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private static final Pattern UPDATE_TARGET_PATTERN =
            Pattern.compile("#\\s*更新对象\\s*[:：]\\s*(.+?)\\s*$", Pattern.MULTILINE);

    /**
     * 解析头部注释
     */
    public void parse(String content, ProcMetadata metadata) {
        Matcher m = PROC_NAME_PATTERN.matcher(content);
        if (m.find()) {
            String procName = m.group(1).trim();
            if (procName.endsWith(".py")) {
                procName = procName.substring(0, procName.length() - 3);
            }
            metadata.setProcName(procName);
        }

        m = PROC_DESC_PATTERN.matcher(content);
        if (m.find()) {
            metadata.setProcDesc(m.group(1).trim());
        }

        m = UPDATE_TARGET_PATTERN.matcher(content);
        if (m.find()) {
            String target = m.group(1).trim();
            metadata.setUpdateTarget(target);
            parseUpdateTargetParts(target, metadata);
        }
    }

    /**
     * 拆分更新对象的 schema 与表名
     * 支持形如: CDM.C_AGT_DEPOSIT_ACCT_INF 或 {CDM}.C_AGT_DEPOSIT_ACCT_INF
     */
    private void parseUpdateTargetParts(String target, ProcMetadata metadata) {
        if (target == null || target.isEmpty()) {
            return;
        }
        // 去掉可能的 { }
        String cleaned = target.replace("{", "").replace("}", "");
        int dot = cleaned.indexOf('.');
        if (dot > 0) {
            metadata.setUpdateTargetSchema(cleaned.substring(0, dot).trim().toUpperCase());
            metadata.setUpdateTargetTable(cleaned.substring(dot + 1).trim().toUpperCase());
        } else {
            metadata.setUpdateTargetTable(cleaned.trim().toUpperCase());
        }
    }
}
