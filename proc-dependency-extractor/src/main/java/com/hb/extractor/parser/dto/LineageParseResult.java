package com.hb.extractor.parser.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 血缘解析结果
 */
@Data
public class LineageParseResult {
    private List<LineageEdge> edges = new ArrayList<>();
    private List<LineageFilter> filters = new ArrayList<>();
    /** 字段级映射 */
    private List<FieldMapping> fieldMappings = new ArrayList<>();
    /** SQL 块信息（含行号） */
    private List<SqlBlockInfoDTO> sqlBlocks = new ArrayList<>();
    /** SQL 块解析失败明细 */
    private List<ParseFailure> failures = new ArrayList<>();

    /**
     * 记录一块解析失败。SQL 预览保留完整内容（不再截断，便于详情弹窗查看全文）；
     * 失败原因转为可读的中文说明并附带原始错误串。
     */
    public void recordFailure(Integer blockSeq, String blockType, String reason, String sqlContent) {
        ParseFailure f = new ParseFailure();
        f.setBlockSeq(blockSeq);
        f.setBlockType(blockType != null ? blockType : classifyBlockType(sqlContent));
        f.setFailReason(humanizeReason(reason));
        f.setSqlPreview(sqlContent);
        failures.add(f);
    }

    /**
     * 将 JsqlParser/解析器的原始异常串翻译为便于人类理解的中文说明。
     * 尽量给出出错位置（第几行、第几列）和具体识别不了的标记，便于对照 SQL 定位；
     * 无法识别的错误类型仍保留原始信息。
     */
    private static final Pattern PTN_LINE_COL = Pattern.compile("at line\\s+(\\d+),\\s+column\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PTN_TOKEN = Pattern.compile("token\\s*:\\s*\\\"([^\\\"]{1,60})\\\"");

    private static String humanizeReason(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "未知解析错误";
        String upper = raw.toUpperCase();

        // 提取出错位置
        int line = -1, col = -1;
        Matcher lm = PTN_LINE_COL.matcher(raw);
        if (lm.find()) {
            line = Integer.parseInt(lm.group(1));
            col = Integer.parseInt(lm.group(2));
        }
        String loc = (line > 0) ? ("第 " + line + " 行、第 " + col + " 列") : "该段内容";

        StringBuilder sb = new StringBuilder();
        if (upper.contains("ENCOUNTERED UNEXPECTED TOKEN") || upper.contains("ENCOUNTERED")) {
            String token = null;
            Matcher tm = PTN_TOKEN.matcher(raw);
            if (tm.find()) {
                token = tm.group(1).replace("\\'", "'");
            }
            boolean isEof = upper.contains("ENCOUNTERED \"<EOF>\"");
            if (isEof) {
                sb.append("该 SQL 在").append(loc).append("意外提前结束：语句没有完整收尾（可能缺分号、末尾被截断，或这里原本就是非 SQL 内容）。");
            } else if (token != null) {
                sb.append("该 SQL 在").append(loc).append("的 “").append(token)
                        .append("” 处无法识别，解析在此中断。");
            } else {
                sb.append("该 SQL 在").append(loc).append("存在解析器不识别的语法标记，解析在此中断。");
            }
            // 常见情形的补充说明：帮助区分"真语法错误"与"被误切的非 SQL 片段"
            sb.append(" 常见原因：不受支持的特殊关键字/方言写法偶发不兼容，或该内容本身不是一条完整 SQL（注释、SQL 片段被切块器误切出来）。");
        } else if (upper.contains("UNSUPPORTED")) {
            sb.append("该 SQL 使用了暂时不支持的特性/语法（").append(loc).append("）。");
        } else if (upper.contains("SOLEXCEPTION") || upper.contains("JCCSQLSCHEMAPARSEREXCEPTION")) {
            sb.append("该 SQL 语法解析异常（").append(loc).append("）。");
        } else {
            sb.append("该 SQL 块解析失败（字段级血缘在此块断裂），位置：").append(loc).append("。");
        }
        // 附带原始错误便于技术定位
        sb.append("\n原始错误: ").append(raw);
        return sb.toString();
    }

    /** 依据 SQL 前缀粗判块类型（与 sql_block_info 判定一致） */
    public static String classifyBlockType(String sql) {
        if (sql == null) return "OTHER";
        String c = sql.trim().toUpperCase();
        if (c.startsWith("CREATE")) return "CREATE_TEMP";
        if (c.startsWith("INSERT")) return "INSERT_SELECT";
        return "OTHER";
    }
}
