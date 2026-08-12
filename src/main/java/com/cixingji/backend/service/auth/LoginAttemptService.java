package com.cixingji.backend.service.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MINUTES = 15L;
    private static final String FAILURE_PREFIX = "auth:login-fail:";
    private static final String LOCK_PREFIX = "auth:login-lock:";

    private final StringRedisTemplate redis;

    public LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLocked(String username, String ipAddress) {
        return Boolean.TRUE.equals(redis.hasKey(lockKey("account", username)))
                || Boolean.TRUE.equals(redis.hasKey(lockKey("ip", ipAddress)));
    }

    public void recordFailure(String username, String ipAddress) {
        record("account", username);
        record("ip", ipAddress);
    }

    public void reset(String username, String ipAddress) {
        redis.delete(failureKey("account", username));
        redis.delete(failureKey("ip", ipAddress));
    }

    private void record(String kind, String value) {
        String failureKey = failureKey(kind, value);
        Long count = redis.opsForValue().increment(failureKey);
        if (count != null && count == 1L) {
            redis.expire(failureKey, LOCK_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count >= MAX_FAILURES) {
            redis.opsForValue().set(lockKey(kind, value), "1", LOCK_MINUTES, TimeUnit.MINUTES);
            redis.delete(failureKey);
        }
    }

    private String failureKey(String kind, String value) {
        return FAILURE_PREFIX + kind + ":" + digest(value);
    }

    private String lockKey(String kind, String value) {
        return LOCK_PREFIX + kind + ":" + digest(value);
    }

    private String digest(String value) {
        try {
            byte[] bytes = String.valueOf(value).toLowerCase().getBytes(StandardCharsets.UTF_8);
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash login identity", e);
        }
    }
}
