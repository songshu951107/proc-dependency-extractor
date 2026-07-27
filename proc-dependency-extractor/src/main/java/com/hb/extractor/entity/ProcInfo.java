package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储过程信息实体
 */
@Data
@TableName("proc_info")
public class ProcInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 存储过程名(文件名去.py) */
    private String procName;

    /** 文件名 */
    private String procFileName;

    /** 完整路径 */
    private String filePath;

    /** 功能说明 */
    private String procDesc;

    /** 更新对象(头部#更新对象,如CDM.C_AGT_DEPOSIT_ACCT_INF) */
    private String updateTarget;

    /** 真实依赖表数量(去重,不含临时表,不含更新目标表) */
    private Integer tableCount;

    /** 临时表数量(VT_前缀) */
    private Integer tempTableCount;

    /** 提取时间 */
    private LocalDateTime extractTime;

    /** 入库时间 */
    private LocalDateTime createTime;
}
