package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_menu")
public class SysMenu {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String menuCode;
    private String menuName;
    private String path;
    private String icon;
    private Long parentId;
    private Integer sortOrder;
    private String type;
}