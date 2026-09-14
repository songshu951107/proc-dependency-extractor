package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.LineageParseResult;
import com.hb.extractor.parser.dto.ProcMetadata;
import com.hb.extractor.parser.dto.SqlBlockInfoDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Python 存储过程主解析器(编排)
 *
 * 解析流程:
 * 1. 读取 .py 文件内容
 * 2. 提取头部注释元数据(脚本名称/功能说明/更新对象)
 * 3. 解析 schema 变量映射(CDM=etl.dwa_db_cdm)
 * 4. 抽取三引号 SQL 块
 * 5. 从 SQL 中提取临时表和依赖表
 */
@Component
public class PyProcParser {

    @Autowired
    private ProcHeaderParser headerParser;

    @Autowired
    private SchemaVarResolver schemaVarResolver;

    @Autowired
    private SqlBlockExtractor sqlBlockExtractor;

    @Autowired
    private SqlTableExtractor sqlTableExtractor;

    @Autowired
    private LineageExtractor lineageExtractor;

    /**
     * 解析单个 Python 存储过程文件
     *
     * @param filePath 文件路径
     * @return 解析结果
     */
    /**
     * 自动检测文件编码：优先 UTF-8 严格解码，失败则回退 GBK（兼容 # coding=gbk 脚本）。
     */
    private String readContentWithEncodingDetection(Path filePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    public ProcMetadata parse(Path filePath) throws IOException {
        String content = readContentWithEncodingDetection(filePath);

        ProcMetadata metadata = new ProcMetadata();
        metadata.setFilePath(filePath.toString());
        metadata.setProcFileName(filePath.getFileName().toString());
        metadata.setExtractTime(LocalDateTime.now());

        // 如果头部未提供脚本名称,使用文件名(去.py)
        String fileName = filePath.getFileName().toString();
        if (fileName.endsWith(".py")) {
            metadata.setProcName(fileName.substring(0, fileName.length() - 3));
        } else {
            metadata.setProcName(fileName);
        }

        // 1. 头部元数据
        headerParser.parse(content, metadata);

        // 2. schema 变量映射
        metadata.setSchemaVarMap(schemaVarResolver.resolve(content));

        // 3. 抽取 SQL 块
        String sql = sqlBlockExtractor.extract(content);

        // 4. 提取表名
        sqlTableExtractor.extract(sql, metadata);

        // 5. 血缘派生关系（新增）
        List<SqlBlockInfoDTO> blockInfos = sqlBlockExtractor.extractBlocksWithMeta(content);
        List<String> blocks = new ArrayList<>();
        for (SqlBlockInfoDTO bi : blockInfos) {
            blocks.add(bi.getContent());
        }
        LineageParseResult lineageResult = lineageExtractor.extract(
                blocks, metadata.getSchemaVarMap(), metadata.getUpdateTarget());
        lineageResult.setSqlBlocks(blockInfos);
        metadata.setLineageResult(lineageResult);

        return metadata;
    }
}
