package com.hb.extractor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 提取请求
 */
@Data
public class ExtractRequest {

    /** 文件或目录路径 */
    @NotBlank(message = "路径不能为空")
    private String path;
}
