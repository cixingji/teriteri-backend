package com.cixingji.backend.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

@Component
@Slf4j
public class JwtUtil {

    public static final long ACCESS_TOKEN_TTL_MILLIS = 30L * 60L * 1000L;

    private static volatile SecretKey tokenSecret;

    @Value("${auth.jwt.secret:}")
    private String configuredSecret;

    @Value("${auth.jwt.issuer:cixingji-teriteri}")
    private String issuer;

    @Value("${auth.jwt.audience:teriteri-web}")
    private String audience;

    private final Environment environment;

    public JwtUtil(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void initializeKey() {
        if (!StringUtils.hasText(configuredSecret)) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("AUTH_JWT_SECRET must be configured in production");
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            tokenSecret = new SecretKeySpec(generated, "HmacSHA256");
            log.warn("auth.jwt.secret is not configured; using an ephemeral development key");
            return;
        }
        tokenSecret = new SecretKeySpec(normalizeKey(configuredSecret), "HmacSHA256");
    }

    public String createAccessToken(Integer userId, String scope, String sessionId) {
        long nowMillis = System.currentTimeMillis();
        return Jwts.builder()
                .setId(sessionId)
                .setSubject(String.valueOf(userId))
                .claim("type", "access")
                .claim("scope", scope)
                .claim("role", scope)
                .setIssuer(issuer)
                .setAudience(audience)
                .setIssuedAt(new Date(nowMillis))
                .setExpiration(new Date(nowMillis + ACCESS_TOKEN_TTL_MILLIS))
                .signWith(requireSecret(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parseAccessToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(requireSecret())
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return "access".equals(claims.get("type", String.class)) ? claims : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Claims getAllClaimsFromToken(String token) {
        if (!StringUtils.hasText(token) || tokenSecret == null) {
            return null;
        }
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(requireSecret())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getSubjectFromToken(String token) {
        Claims claims = getAllClaimsFromToken(token);
        return claims == null ? null : claims.getSubject();
    }

    public static String getIdFromToken(String token) {
        Claims claims = getAllClaimsFromToken(token);
        return claims == null ? null : claims.getId();
    }

    public static String getClaimFromToken(String token, String name) {
        Claims claims = getAllClaimsFromToken(token);
        if (claims == null || !claims.containsKey(name)) {
            return "";
        }
        return String.valueOf(claims.get(name));
    }

    private static byte[] normalizeKey(String value) {
        byte[] source;
        try {
            source = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            source = value.getBytes(StandardCharsets.UTF_8);
        }
        if (source.length >= 32) {
            return source;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(source);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize the JWT signing key", e);
        }
    }

    private static SecretKey requireSecret() {
        if (tokenSecret == null) {
            throw new IllegalStateException("JWT signing key is not initialized");
        }
        return tokenSecret;
    }
}
