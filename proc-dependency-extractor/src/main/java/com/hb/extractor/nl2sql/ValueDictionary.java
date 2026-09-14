package com.hb.extractor.nl2sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NL2SQL 领域知识常量：业务专有词加权与枚举字段码值字典。
 * 这些是领域静态知识，硬编码以便稳定使用；如需调整码值/业务词直接改这里。
 * 码值请按贵行实际标准校准。
 */
public final class ValueDictionary {

    /** 业务词加权：业务专有词 -> 候选表名目标关键词。问题含该词时命中的候选表加分，优先选对业务表。 */
    public static Map<String, List<String>> businessTerms() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("信用卡", List.of("credit_card", "ccrd"));
        m.put("存款", List.of("deposit"));
        m.put("贷款", List.of("loan"));
        return m;
    }

    /** 业务词补充召回：业务专有词 -> 权威业务账户表全名白名单（精确点召回，非模糊 LIKE）。
     *  这些表是该业务的主数据表，应全量保留字段并优先作为数据来源。 */
    public static Map<String, List<String>> businessTermTables() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("信用卡", List.of("cdm.c_agt_credit_card_acct"));
        return m;
    }

    /** 枚举字段码值字典：字段语义名 -> 码值字符串"code=含义;code=含义..."。约束 LLM 只能使用已知码值。 */
    public static Map<String, String> valueDict() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("五级分类", "1=正常类;2=关注类;3=次级类;4=可疑类;5=损失类");
        return m;
    }

    private ValueDictionary() {}
}