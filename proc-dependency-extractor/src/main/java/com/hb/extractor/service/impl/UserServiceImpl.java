package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hb.extractor.dto.ImportResultDTO;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysRoleMapper;
import com.hb.extractor.mapper.SysUserMapper;
import com.hb.extractor.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    public static final String DEFAULT_PASSWORD = "1234.Com";

    private final SysUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @org.springframework.beans.factory.annotation.Autowired
    private SysRoleMapper roleMapper;

    @Override
    public IPage<SysUser> page(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
        }
        qw.orderByDesc(SysUser::getCreateTime);
        return userMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    @Override
    public void create(UserSaveRequest req) {
        checkDuplicate(req.getUsername(), null);
        SysUser user = new SysUser();
        apply(user, req);
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setMustChangePwd(1);
        userMapper.insert(user);
    }

    @Override
    public void update(UserSaveRequest req) {
        SysUser user = userMapper.selectById(req.getId());
        if (user == null) throw new BusinessException(400, "用户不存在");
        checkDuplicate(req.getUsername(), req.getId());
        apply(user, req);
        userMapper.updateById(user);
    }

    @Override
    public void delete(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user != null && "admin".equals(user.getUsername())) {
            throw new BusinessException(400, "内置管理员账号不可删除");
        }
        userMapper.deleteById(id);
    }

    @Override
    public void resetPassword(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(400, "用户不存在");
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setMustChangePwd(1);
        userMapper.updateById(user);
    }

    @Override
    public List<SysUser> listAll() {
        return userMapper.selectList(null);
    }

    private void apply(SysUser user, UserSaveRequest req) {
        user.setUsername(req.getUsername());
        user.setRealName(req.getRealName());
        user.setRoleId(req.getRoleId());
        user.setDept(req.getDept());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus() == null ? 1 : req.getStatus());
    }

    private void checkDuplicate(String username, Long excludeId) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username);
        if (excludeId != null) qw.ne(SysUser::getId, excludeId);
        if (userMapper.selectCount(qw) > 0) {
            throw new BusinessException(409, "用户名已存在");
        }
    }

    @Override
    public ImportResultDTO importExcel(org.springframework.web.multipart.MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            throw new BusinessException(400, "文件格式错误，仅支持 .xlsx/.xls");
        }
        ImportResultDTO result = new ImportResultDTO();
        java.util.Map<String, Long> roleMap = new java.util.HashMap<>();
        roleMapper.selectList(null).forEach(r -> roleMap.put(r.getRoleName(), r.getId()));
        roleMap.put("管理员", 1L);
        roleMap.put("普通用户", 2L);

        try (org.apache.poi.ss.usermodel.Workbook wb =
                 org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream())) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) {
                throw new BusinessException(400, "Excel 内容为空");
            }
            String header0 = cell(sheet.getRow(0), 0);
            if (header0 == null || !header0.contains("用户名")) {
                throw new BusinessException(400, "缺少必填列「用户名」");
            }
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row == null) continue;
                String username = cell(row, 0);
                if (username == null || username.isBlank()) continue;
                if (userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                        .LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
                    result.setSkipCount(result.getSkipCount() + 1);
                    result.getSkipReasons().add("第" + (i + 1) + "行 " + username + "：用户名已存在");
                    continue;
                }
                try {
                    SysUser user = new SysUser();
                    user.setUsername(username);
                    user.setRealName(cell(row, 1));
                    String roleName = cell(row, 2);
                    user.setRoleId(roleMap.getOrDefault(roleName, 2L));
                    user.setDept(cell(row, 3));
                    user.setEmail(cell(row, 4));
                    user.setPhone(cell(row, 5));
                    user.setStatus("禁用".equals(cell(row, 6)) ? 0 : 1);
                    user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
                    user.setMustChangePwd(1);
                    userMapper.insert(user);
                    result.setSuccessCount(result.getSuccessCount() + 1);
                } catch (Exception ex) {
                    result.setSkipCount(result.getSkipCount() + 1);
                    result.getSkipReasons().add("第" + (i + 1) + "行 " + username + "：" + ex.getMessage());
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "Excel 解析失败：" + e.getMessage());
        }
        return result;
    }

    private String cell(org.apache.poi.ss.usermodel.Row row, int idx) {
        org.apache.poi.ss.usermodel.Cell c = row.getCell(idx);
        if (c == null) return null;
        c.setCellType(org.apache.poi.ss.usermodel.CellType.STRING);
        String v = c.getStringCellValue();
        return v == null ? null : v.trim();
    }
}