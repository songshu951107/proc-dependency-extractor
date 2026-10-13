package com.hb.extractor.service.impl;
import com.hb.extractor.entity.SysRole;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysRoleMapper;
import com.hb.extractor.mapper.SysUserMapper;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.service.RoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;

    @Override public List<SysRole> listAll() { return roleMapper.selectList(null); }

    @Override public void create(SysRole role) { roleMapper.insert(role); }

    @Override public void update(SysRole role) { roleMapper.updateById(role); }

    @Override public void delete(Long id) {
        if ("ADMIN".equals(roleMapper.selectById(id).getRoleCode())) {
            throw new BusinessException(400, "内置管理员角色不可删除");
        }
        long used = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleId, id));
        if (used > 0) throw new BusinessException(400, "该角色下仍有用户，无法删除");
        roleMapper.deleteById(id);
    }
}