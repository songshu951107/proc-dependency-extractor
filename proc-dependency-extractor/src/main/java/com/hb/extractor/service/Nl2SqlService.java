package com.hb.extractor.service;

import com.hb.extractor.dto.nl2sql.Nl2SqlResult;

public interface Nl2SqlService {
    /** 中文问句 → GBase SQL（生成 + 校验 + 重试） */
    Nl2SqlResult generate(String question);
}