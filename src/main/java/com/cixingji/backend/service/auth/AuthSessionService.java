package com.cixingji.backend.service.auth;

import com.cixingji.backend.pojo.dto.auth.AuthSession;
import com.cixingji.backend.pojo.dto.auth.AuthSessionView;
import com.cixingji.backend.pojo.dto.auth.AuthTokenPair;
import com.cixingji.backend.utils.JwtUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AuthSessionService {

    public static final long REFRESH_TOKEN_TTL_SECONDS = 30L * 24L * 60L * 60L;

    private static final String SESSION_PREFIX = "auth:session:";
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String USER_SESSIONS_PREFIX = "auth:user-sessions:";
    private static final String REVOKED_PREFIX = "revoked:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.session.max-devices:10}")
    private int maxDevices;

    public AuthSessionService(StringRedisTemplate redis, ObjectMapper objectMapper, JwtUtil jwtUtil) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.jwtUtil = jwtUtil;
    }

    public AuthTokenPair createSession(Integer userId, String scope, String deviceName, String ipAddress) {
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String refreshToken = randomToken();
        String refreshHash = hash(refreshToken);
        AuthSession session = new AuthSession(
                sessionId,
                userId,
                normalizeScope(scope),
                normalizeDeviceName(deviceName),
                normalizeIp(ipAddress),
                now,
                now,
                now + TimeUnit.SECONDS.toMillis(REFRESH_TOKEN_TTL_SECONDS),
                refreshHash
        );
        saveSession(session);
        redis.opsForValue().set(refreshKey(refreshHash), sessionId, REFRESH_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        String userSessionsKey = userSessionsKey(userId);
        redis.opsForZSet().add(userSessionsKey, sessionId, now);
        redis.expire(userSessionsKey, REFRESH_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        enforceDeviceLimit(userId);
        return tokenPair(session, refreshToken);
    }

    public AuthSession validateAccessToken(String accessToken) {
        Claims claims = jwtUtil.parseAccessToken(accessToken);
        if (claims == null) {
            return null;
        }
        String sessionId = claims.getId();
        AuthSession session = getSession(sessionId);
        if (session == null || session.getExpiresAt() <= System.currentTimeMillis()) {
            return null;
        }
        if (!String.valueOf(session.getUserId()).equals(claims.getSubject())
                || !session.getScope().equals(claims.get("scope", String.class))) {
            return null;
        }
        touchSession(session);
        return session;
    }

    public AuthTokenPair refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return null;
        }
        String oldHash = hash(refreshToken);
        String value = redis.opsForValue().get(refreshKey(oldHash));
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.startsWith(REVOKED_PREFIX)) {
            revokeSession(value.substring(REVOKED_PREFIX.length()));
            return null;
        }
        AuthSession session = getSession(value);
        if (session == null || !oldHash.equals(session.getRefreshTokenHash())) {
            revokeSession(value);
            return null;
        }
        long now = System.currentTimeMillis();
        String newRefreshToken = randomToken();
        String newHash = hash(newRefreshToken);
        long ttl = REFRESH_TOKEN_TTL_SECONDS;
        redis.opsForValue().set(refreshKey(oldHash), REVOKED_PREFIX + session.getSessionId(), ttl, TimeUnit.SECONDS);
        redis.opsForValue().set(refreshKey(newHash), session.getSessionId(), ttl, TimeUnit.SECONDS);
        session.setRefreshTokenHash(newHash);
        session.setLastActiveAt(now);
        session.setExpiresAt(now + TimeUnit.SECONDS.toMillis(ttl));
        saveSession(session);
        redis.opsForZSet().add(userSessionsKey(session.getUserId()), session.getSessionId(), now);
        return tokenPair(session, newRefreshToken);
    }

    public List<AuthSessionView> listSessions(Integer userId, String currentSessionId) {
        Set<String> ids = redis.opsForZSet().reverseRange(userSessionsKey(userId), 0, -1);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<AuthSessionView> result = new ArrayList<>();
        for (String id : ids) {
            AuthSession session = getSession(id);
            if (session == null) {
                redis.opsForZSet().remove(userSessionsKey(userId), id);
                continue;
            }
            result.add(new AuthSessionView(
                    session.getSessionId(),
                    session.getScope(),
                    session.getDeviceName(),
                    session.getIpAddress(),
                    session.getCreatedAt(),
                    session.getLastActiveAt(),
                    session.getExpiresAt(),
                    session.getSessionId().equals(currentSessionId)
            ));
        }
        return result;
    }

    public boolean revokeOwnedSession(Integer userId, String sessionId) {
        AuthSession session = getSession(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            return false;
        }
        revokeSession(sessionId);
        return true;
    }

    public void revokeSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        AuthSession session = getSession(sessionId);
        if (session != null) {
            redis.delete(refreshKey(session.getRefreshTokenHash()));
            redis.opsForZSet().remove(userSessionsKey(session.getUserId()), sessionId);
        }
        redis.delete(sessionKey(sessionId));
    }

    public void revokeAll(Integer userId) {
        Set<String> ids = redis.opsForZSet().range(userSessionsKey(userId), 0, -1);
        if (ids != null) {
            for (String id : new ArrayList<>(ids)) {
                revokeSession(id);
            }
        }
        redis.delete(userSessionsKey(userId));
    }

    public AuthSession getSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String json = redis.opsForValue().get(sessionKey(sessionId));
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AuthSession.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid authentication session data", e);
        }
    }

    private void touchSession(AuthSession session) {
        long now = System.currentTimeMillis();
        if (now - session.getLastActiveAt() < TimeUnit.MINUTES.toMillis(1)) {
            return;
        }
        session.setLastActiveAt(now);
        saveSession(session);
        redis.opsForZSet().add(userSessionsKey(session.getUserId()), session.getSessionId(), now);
    }

    private void saveSession(AuthSession session) {
        long remaining = Math.max(1L, session.getExpiresAt() - System.currentTimeMillis());
        try {
            redis.opsForValue().set(
                    sessionKey(session.getSessionId()),
                    objectMapper.writeValueAsString(session),
                    remaining,
                    TimeUnit.MILLISECONDS
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize authentication session", e);
        }
    }

    private void enforceDeviceLimit(Integer userId) {
        String key = userSessionsKey(userId);
        Long count = redis.opsForZSet().zCard(key);
        if (count == null || count <= maxDevices) {
            return;
        }
        Set<String> expired = redis.opsForZSet().range(key, 0, count - maxDevices - 1);
        if (expired != null) {
            for (String sessionId : new ArrayList<>(expired)) {
                revokeSession(sessionId);
            }
        }
    }

    private AuthTokenPair tokenPair(AuthSession session, String refreshToken) {
        String accessToken = jwtUtil.createAccessToken(session.getUserId(), session.getScope(), session.getSessionId());
        return new AuthTokenPair(
                accessToken,
                refreshToken,
                TimeUnit.MILLISECONDS.toSeconds(JwtUtil.ACCESS_TOKEN_TTL_MILLIS),
                REFRESH_TOKEN_TTL_SECONDS,
                session.getSessionId()
        );
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash refresh token", e);
        }
    }

    private String normalizeScope(String scope) {
        return "admin".equalsIgnoreCase(scope) ? "admin" : "user";
    }

    private String normalizeDeviceName(String value) {
        if (!StringUtils.hasText(value)) {
            return "Unknown device";
        }
        String trimmed = value.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private String normalizeIp(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String refreshKey(String refreshHash) {
        return REFRESH_PREFIX + refreshHash;
    }

    private String userSessionsKey(Integer userId) {
        return USER_SESSIONS_PREFIX + userId;
    }
}
