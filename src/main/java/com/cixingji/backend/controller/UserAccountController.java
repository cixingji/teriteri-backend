package com.cixingji.backend.controller;

import com.cixingji.backend.pojo.dto.auth.AuthTokenPair;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.service.auth.AuthSessionService;
import com.cixingji.backend.service.user.UserAccountService;
import com.cixingji.backend.service.utils.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
public class UserAccountController {

    public static final String REFRESH_COOKIE = "teri_refresh_token";
    public static final String ADMIN_REFRESH_COOKIE = "teri_admin_refresh_token";

    private final UserAccountService userAccountService;
    private final AuthSessionService authSessionService;
    private final CurrentUser currentUser;

    @Value("${auth.cookie.secure:false}")
    private boolean secureCookie;

    public UserAccountController(UserAccountService userAccountService,
                                 AuthSessionService authSessionService,
                                 CurrentUser currentUser) {
        this.userAccountService = userAccountService;
        this.authSessionService = authSessionService;
        this.currentUser = currentUser;
    }

    @PostMapping("/user/account/register")
    public ResponseEntity<CustomResponse> register(@RequestBody Map<String, String> body,
                                                   HttpServletRequest request) {
        try {
            CustomResponse response = userAccountService.register(
                    body.get("username"),
                    body.get("password"),
                    body.get("confirmedPassword"),
                    deviceName(request),
                    clientIp(request)
            );
            return withRefreshCookie(response, "user");
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new CustomResponse(500, "Registration failed", null));
        }
    }

    @PostMapping("/user/account/login")
    public ResponseEntity<CustomResponse> login(@RequestBody Map<String, String> body,
                                                HttpServletRequest request) {
        CustomResponse response = userAccountService.login(
                body.get("username"), body.get("password"), deviceName(request), clientIp(request));
        return withRefreshCookie(response, "user");
    }

    @PostMapping("/admin/account/login")
    public ResponseEntity<CustomResponse> adminLogin(@RequestBody Map<String, String> body,
                                                     HttpServletRequest request) {
        CustomResponse response = userAccountService.adminLogin(
                body.get("username"), body.get("password"), deviceName(request), clientIp(request));
        return withRefreshCookie(response, "admin");
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<CustomResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String userRefreshToken,
            @CookieValue(name = ADMIN_REFRESH_COOKIE, required = false) String adminRefreshToken,
            @RequestHeader(name = "X-Auth-Scope", required = false) String requestedScope) {
        boolean adminScope = "admin".equalsIgnoreCase(requestedScope);
        String refreshToken = adminScope ? adminRefreshToken : userRefreshToken;
        AuthTokenPair pair = authSessionService.refresh(refreshToken);
        if (pair == null) {
            return ResponseEntity.status(401)
                    .header(HttpHeaders.SET_COOKIE, clearRefreshCookie(adminScope).toString())
                    .body(new CustomResponse(401, "Refresh token is invalid or expired", null));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("token", pair.getAccessToken());
        data.put("accessToken", pair.getAccessToken());
        data.put("accessTokenExpiresIn", pair.getAccessTokenExpiresInSeconds());
        data.put("sessionId", pair.getSessionId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(pair.getRefreshToken(), adminScope).toString())
                .body(new CustomResponse(200, "Token refreshed", data));
    }

    @GetMapping("/auth/sessions")
    public CustomResponse sessions() {
        return new CustomResponse(
                200,
                "OK",
                authSessionService.listSessions(currentUser.getUserId(), currentUser.getSessionId())
        );
    }

    @DeleteMapping("/auth/sessions/{sessionId}")
    public ResponseEntity<CustomResponse> revokeSession(@PathVariable String sessionId) {
        boolean revoked = authSessionService.revokeOwnedSession(currentUser.getUserId(), sessionId);
        if (!revoked) {
            return ResponseEntity.status(404)
                    .body(new CustomResponse(404, "Session not found", null));
        }
        return ResponseEntity.ok(new CustomResponse(200, "Session revoked", null));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<CustomResponse> logoutPost(
            @RequestHeader(name = "X-Auth-Scope", required = false) String requestedScope) {
        userAccountService.logout();
        return loggedOutResponse("Logged out", "admin".equalsIgnoreCase(requestedScope));
    }

    @PostMapping("/auth/logout-all")
    public ResponseEntity<CustomResponse> logoutAll() {
        userAccountService.logoutAll();
        return loggedOutAllResponse("Logged out from all devices");
    }

    @GetMapping("/user/account/logout")
    public ResponseEntity<CustomResponse> logout() {
        userAccountService.logout();
        return loggedOutResponse("Logged out", false);
    }

    @GetMapping("/admin/account/logout")
    public ResponseEntity<CustomResponse> adminLogout() {
        userAccountService.adminLogout();
        return loggedOutResponse("Logged out", true);
    }

    @GetMapping("/user/personal/info")
    public ResponseEntity<CustomResponse> personalInfo() {
        return response(userAccountService.personalInfo());
    }

    @GetMapping("/admin/personal/info")
    public ResponseEntity<CustomResponse> adminPersonalInfo() {
        return response(userAccountService.adminPersonalInfo());
    }

    @PostMapping("/user/password/update")
    public ResponseEntity<CustomResponse> updatePassword(@RequestParam("pw") String currentPassword,
                                                         @RequestParam("npw") String newPassword) {
        CustomResponse response = userAccountService.updatePassword(currentPassword, newPassword);
        if (response.getCode() == 200) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            clearRefreshCookie(false).toString(), clearRefreshCookie(true).toString())
                    .body(response);
        }
        return response(response);
    }

    @PostMapping("/user/password/set")
    public ResponseEntity<CustomResponse> setInitialPassword(@RequestBody Map<String, String> body) {
        CustomResponse response = userAccountService.setInitialPassword(body.get("password"));
        if (response.getCode() == 200) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            clearRefreshCookie(false).toString(), clearRefreshCookie(true).toString())
                    .body(response);
        }
        return response(response);
    }

    private ResponseEntity<CustomResponse> withRefreshCookie(CustomResponse response, String scope) {
        if (response.getCode() != 200 || !(response.getData() instanceof Map)) {
            return response(response);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        Object refreshToken = data.remove("refreshToken");
        if (refreshToken == null) {
            return response(response);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(String.valueOf(refreshToken), "admin".equals(scope)).toString())
                .body(response);
    }

    private ResponseEntity<CustomResponse> response(CustomResponse response) {
        return ResponseEntity.status(response.getCode()).body(response);
    }

    private ResponseEntity<CustomResponse> loggedOutResponse(String message, boolean adminScope) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie(adminScope).toString())
                .body(new CustomResponse(200, message, null));
    }

    private ResponseEntity<CustomResponse> loggedOutAllResponse(String message) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        clearRefreshCookie(false).toString(), clearRefreshCookie(true).toString())
                .body(new CustomResponse(200, message, null));
    }

    private ResponseCookie refreshCookie(String value, boolean adminScope) {
        return ResponseCookie.from(adminScope ? ADMIN_REFRESH_COOKIE : REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(AuthSessionService.REFRESH_TOKEN_TTL_SECONDS))
                .build();
    }

    private ResponseCookie clearRefreshCookie(boolean adminScope) {
        return ResponseCookie.from(adminScope ? ADMIN_REFRESH_COOKIE : REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private String deviceName(HttpServletRequest request) {
        String explicit = request.getHeader("X-Device-Name");
        return explicit == null || explicit.trim().isEmpty()
                ? request.getHeader("User-Agent")
                : explicit;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
