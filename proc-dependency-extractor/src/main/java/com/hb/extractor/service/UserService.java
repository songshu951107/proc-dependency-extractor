package com.hb.extractor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.ImportResultDTO;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;

import java.util.List;

public interface UserService {
    IPage<SysUser> page(int pageNum, int pageSize, String keyword);
    void create(UserSaveRequest request);
    void update(UserSaveRequest request);
    void delete(Long id);
    void resetPassword(Long id);
    List<SysUser> listAll();
    ImportResultDTO importExcel(org.springframework.web.multipart.MultipartFile file);
}