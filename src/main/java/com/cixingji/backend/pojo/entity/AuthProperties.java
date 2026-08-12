package com.cixingji.backend.pojo.entity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;




@Data // lombok
@Configuration
@ConfigurationProperties(prefix = "justauth")
public class AuthProperties {
    private AuthConfig gitee;
    private AuthConfig github;

    @Data
    public static class AuthConfig {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
    }
}