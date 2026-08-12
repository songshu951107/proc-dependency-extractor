package com.hb.extractor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置(跨域 + SPA 前端路由转发)
 * 开发阶段前端 Vite 默认端口 5173,后端 8080,需允许跨域
 * 生产打包后前端资源在 static 目录,SPA 路由刷新需转发到 index.html
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA 前端路由: 非api请求统一转发到 index.html,避免刷新 404
        registry.addViewController("/procs").setViewName("forward:/index.html");
        registry.addViewController("/procs/**").setViewName("forward:/index.html");
        registry.addViewController("/impact").setViewName("forward:/index.html");
        registry.addViewController("/report-impact").setViewName("forward:/index.html");
    }
}
