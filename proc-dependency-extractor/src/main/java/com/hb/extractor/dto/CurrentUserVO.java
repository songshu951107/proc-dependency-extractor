package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class CurrentUserVO {
    private Long id;
    private String username;
    private String realName;
    private String roleCode;
    private boolean mustChangePwd;
    private List<MenuVO> menus;
}