package com.cixingji.backend.service.traffic;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class TrafficIdentity {
    private TrafficIdentity() { }

    public static String visitorId(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("teri_visitor_id".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().trim().isEmpty()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public static String requestActor(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.trim().isEmpty()) return "TOKEN:" + hash(authorization);
        String visitor = visitorId(request);
        if (visitor != null) return "VISITOR:" + hash(visitor);
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.trim().isEmpty() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return "IP:" + hash(ip == null ? "unknown" : ip);
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
