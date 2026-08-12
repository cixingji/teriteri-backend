package com.cixingji.backend.utils;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    @Test
    void createsAndParsesAccessToken() {
        JwtUtil jwtUtil = configuredJwt(new MockEnvironment(), "test-secret-with-more-than-thirty-two-characters");
        jwtUtil.initializeKey();

        String token = jwtUtil.createAccessToken(42, "admin", "session-123");
        Claims claims = jwtUtil.parseAccessToken(token);

        assertEquals("42", claims.getSubject());
        assertEquals("session-123", claims.getId());
        assertEquals("admin", claims.get("scope", String.class));
        assertEquals("access", claims.get("type", String.class));
    }

    @Test
    void rejectsMalformedToken() {
        JwtUtil jwtUtil = configuredJwt(new MockEnvironment(), "test-secret-with-more-than-thirty-two-characters");
        jwtUtil.initializeKey();

        assertNull(jwtUtil.parseAccessToken("not-a-jwt"));
    }

    @Test
    void productionRequiresConfiguredSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        JwtUtil jwtUtil = configuredJwt(environment, "");

        assertThrows(IllegalStateException.class, jwtUtil::initializeKey);
    }

    private JwtUtil configuredJwt(MockEnvironment environment, String secret) {
        JwtUtil jwtUtil = new JwtUtil(environment);
        ReflectionTestUtils.setField(jwtUtil, "configuredSecret", secret);
        ReflectionTestUtils.setField(jwtUtil, "issuer", "test-issuer");
        ReflectionTestUtils.setField(jwtUtil, "audience", "test-audience");
        return jwtUtil;
    }
}
