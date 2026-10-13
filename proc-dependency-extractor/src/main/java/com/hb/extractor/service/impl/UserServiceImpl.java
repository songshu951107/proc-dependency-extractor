package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.exception.BusinessException;
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
}