package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MenuVO {
    private Long id;
    private String menuCode;
    private String menuName;
    private String path;
    private String icon;
    private Long parentId;
    private Integer sortOrder;
    private String type;
    private List<MenuVO> children = new ArrayList<>();
}