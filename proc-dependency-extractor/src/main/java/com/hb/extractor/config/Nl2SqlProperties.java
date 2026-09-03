package com.hb.extractor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nl2sql")
public class Nl2SqlProperties {
    /** OpenAI 兼容 API key（环境变量注入，不落库） */
    private String apiKey = "";
    /** OpenAI 兼容 base-url */
    private String baseUrl = "https://api.deepseek.com/v1";
    private String model = "deepseek-chat";
    private double temperature = 0;
    /** 候选表上限 */
    private int maxCandidateTables = 10;
    /** 每表字段上限 */
    private int maxFieldsPerTable = 15;
    /** 语法校验失败重试上限 */
    private int maxRetries = 2;
    /** 题集文件路径 */
    private String evalFile = "docs/nl2sql/questions.json";
}