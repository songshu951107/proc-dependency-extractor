package com.hb.extractor.service;
import com.hb.extractor.entity.SysRole;
import java.util.List;
public interface RoleService {
    List<SysRole> listAll();
    void create(SysRole role);
    void update(SysRole role);
    void delete(Long id);
}