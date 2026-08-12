package com.cixingji.backend.pojo.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthSessionView {
    private String sessionId;
    private String scope;
    private String deviceName;
    private String ipAddress;
    private long createdAt;
    private long lastActiveAt;
    private long expiresAt;
    private boolean current;
}
