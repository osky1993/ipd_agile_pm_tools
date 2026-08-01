package com.ipd.toolbox.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipd.toolbox.domain.entity.SysUser;
import com.ipd.toolbox.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动初始化：确保存在演示管理员账号 admin/admin123（兼全部角色）。
 * 密码用 BCrypt 哈希，避免在 SQL 种子中硬编码。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", "admin"));
        if (existing != null && existing > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode("admin123"));
        admin.setDisplayName("系统管理员");
        admin.setEnabled(1);
        admin.setCreatedAt(LocalDateTime.now());
        userMapper.insert(admin);
        userMapper.assignAllRoles(admin.getId());
        log.info("已创建演示管理员账号 admin/admin123 (id={})，并分配全部角色", admin.getId());
    }
}
