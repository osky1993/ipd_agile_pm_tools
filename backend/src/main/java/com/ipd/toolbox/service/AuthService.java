package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.SysUser;
import com.ipd.toolbox.mapper.SysUserMapper;
import com.ipd.toolbox.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Map<String, Object> login(String username, String password) {
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        if (user == null || user.getEnabled() == null || user.getEnabled() != 1) {
            throw new BusinessException(4011, "用户不存在或已停用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(4011, "用户名或密码错误");
        }
        List<String> roles = userMapper.findRoleCodes(user.getId());
        String token = jwtService.generate(user.getId(), user.getUsername(), roles);
        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "roles", roles
        );
    }
}
