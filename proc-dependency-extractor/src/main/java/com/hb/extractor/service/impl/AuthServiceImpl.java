package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.SysLoginLog;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.exception.BusinessException;
import com.hb.extractor.mapper.SysLoginLogMapper;
import com.hb.extractor.mapper.SysUserMapper;
import com.hb.extractor.service.AuthService;
import com.hb.extractor.service.MenuService;
import com.hb.extractor.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Pattern PWD_RULE =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final SysUserMapper userMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final MenuService menuService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            writeLog(user, request.getUsername(), ip, userAgent, false, "用户名或密码错误");
            throw new BusinessException(400, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            writeLog(user, user.getUsername(), ip, userAgent, false, "账号已禁用");
            throw new BusinessException(400, "账号已禁用，请联系管理员");
        }
        writeLog(user, user.getUsername(), ip, userAgent, true, null);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        String roleCode = menuService.roleCodeOf(user.getRoleId());
        String token = jwtUtil.generate(user.getId(), user.getUsername(), roleCode);
        CurrentUserVO vo = toVO(user, roleCode, user.getMustChangePwd() != null && user.getMustChangePwd() == 1);
        return new LoginResponse(token, vo.isMustChangePwd(), vo);
    }

    @Override
    public CurrentUserVO currentUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(401, "用户不存在");
        String roleCode = menuService.roleCodeOf(user.getRoleId());
        return toVO(user, roleCode, user.getMustChangePwd() != null && user.getMustChangePwd() == 1);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(401, "用户不存在");
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        if (request.getNewPassword() == null || !PWD_RULE.matcher(request.getNewPassword()).matches()) {
            throw new BusinessException(400, "新密码须≥8位且含大小写字母和数字");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePwd(0);
        userMapper.updateById(user);
    }

    private CurrentUserVO toVO(SysUser user, String roleCode, boolean mustChangePwd) {
        CurrentUserVO vo = new CurrentUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRoleCode(roleCode);
        vo.setMustChangePwd(mustChangePwd);
        vo.setMenus(menuService.menusOfRole(user.getRoleId()));
        return vo;
    }

    private void writeLog(SysUser user, String username, String ip, String userAgent,
                          boolean success, String failReason) {
        SysLoginLog log = new SysLoginLog();
        log.setUserId(user == null ? null : user.getId());
        log.setUsername(username);
        log.setIp(ip);
        log.setUserAgent(userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 255)));
        log.setSuccess(success ? 1 : 0);
        log.setFailReason(failReason);
        loginLogMapper.insert(log);
    }
}