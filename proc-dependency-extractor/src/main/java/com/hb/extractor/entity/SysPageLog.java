package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_page_log")
public class SysPageLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String pagePath;
    private String pageName;
    private LocalDateTime visitTime;
}