package com.hb.extractor.dto;

import lombok.Data;

/**
 * 受影响物理表明细（含到达它的存储过程）
 */
@Data
public class AffectedTableVO {
    private String name;
    private String procName;
}