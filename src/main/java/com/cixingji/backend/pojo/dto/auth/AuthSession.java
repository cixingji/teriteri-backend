package com.cixingji.backend.pojo.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthSession {
    private String sessionId;
    private Integer userId;
    private String scope;
    private String deviceName;
    private String ipAddress;
    private long createdAt;
    private long lastActiveAt;
    private long expiresAt;
    private String refreshTokenHash;
}
