package com.ipd.toolbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.ipd.toolbox.mapper")
/**
 * 应用启动入口。
 * 约定：
 * - 统一扫描 com.ipd.toolbox.mapper 下的 MyBatis Mapper 接口
 * - 通过 @SpringBootApplication 承载自动配置、组件扫描与 MVC/事务配置入口
 */
public class ToolboxApplication {
    /**
     * JVM 启动时会从此方法进入 Spring 容器，加载全部 @Controller/@Service/@Component Bean。
     * 对接新环境时，关注：
     * 1) active profile 与数据库配置是否落到 Spring Boot 配置源
     * 2) 是否有本地临时文件/证据目录权限问题
     */
    public static void main(String[] args) {
        SpringApplication.run(ToolboxApplication.class, args);
    }
}
