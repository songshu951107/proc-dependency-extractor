package com.hb.extractor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 存储过程依赖表名提取系统启动类
 */
@SpringBootApplication
@MapperScan("com.hb.extractor.mapper")
public class ProcExtractorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcExtractorApplication.class, args);
    }
}
