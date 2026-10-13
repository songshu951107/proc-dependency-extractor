package com.hb.extractor.dto;
import lombok.Data;
@Data public class UserSaveRequest {
    private Long id; private String username; private String realName; private Long roleId;
    private String dept; private String email; private String phone; private Integer status;
}