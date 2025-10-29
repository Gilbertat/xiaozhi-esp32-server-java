package com.xiaozhi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class JwtConfig {

    @Value("${jwt.secret:defaultSecretKey}")
    private String secret;

    @Value("${jwt.expiration:86400}") // 默认24小时
    private Long expiration;

    @Bean
    public String jwtSecret() {
        return secret;
    }

    @Bean
    public Long jwtExpiration() {
        return expiration;
    }
}