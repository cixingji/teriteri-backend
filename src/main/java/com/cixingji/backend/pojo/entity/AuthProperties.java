package com.cixingji.backend.pojo.entity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "justauth")
public class AuthProperties {
    private ProviderTypes type = new ProviderTypes();
    private String frontendRedirectUri = "http://localhost:8080/oauth/callback";

    @Data
    public static class ProviderTypes {
        private ProviderConfig github = new ProviderConfig();
    }

    @Data
    public static class ProviderConfig {
        private String clientId;
        private String clientSecret;
        private String redirectUri = "http://localhost:7070/oauth/callback/github";
    }
}
