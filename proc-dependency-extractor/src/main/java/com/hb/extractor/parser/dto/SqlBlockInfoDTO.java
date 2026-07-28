package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 块信息 DTO（解析期）
 */
@Data
public class SqlBlockInfoDTO {
    /** SQL 块序号（从1开始） */
    private int blockSeq;
    /** SQL 块原文（去注释后） */
    private String content;
    /** 脚本中起始行号（1-based，基于原始 Python 文件） */
    private int startLine;
    /** 脚本中结束行号 */
    private int endLine;
}
