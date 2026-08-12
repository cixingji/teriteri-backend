package com.cixingji.backend.controller;

import com.cixingji.backend.pojo.dto.auth.GithubLoginResult;
import com.cixingji.backend.pojo.entity.AuthProperties;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.service.auth.GithubOAuthService;
import com.cixingji.backend.service.utils.CurrentUser;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthRequest;
import me.zhyd.oauth.utils.AuthStateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Controller
@RequestMapping("/oauth")
public class JustAuthController {

    private static final String STATE_PREFIX = "auth:oauth-state:";

    private final AuthProperties properties;
    private final StringRedisTemplate redis;
    private final GithubOAuthService githubOAuthService;
    private final CurrentUser currentUser;

    @Value("${auth.cookie.secure:false}")
    private boolean secureCookie;

    public JustAuthController(AuthProperties properties,
                              StringRedisTemplate redis,
                              GithubOAuthService githubOAuthService,
                              CurrentUser currentUser) {
        this.properties = properties;
        this.redis = redis;
        this.githubOAuthService = githubOAuthService;
        this.currentUser = currentUser;
    }

    @GetMapping("/render/github")
    public void renderGithub(HttpServletResponse response) throws IOException {
        redirectToGithub(response, "login");
    }

    @GetMapping("/github/bind")
    public void bindGithub(HttpServletResponse response) throws IOException {
        redirectToGithub(response, "bind:" + currentUser.getUserId());
    }

    @GetMapping("/callback/github")
    public void githubCallback(AuthCallback callback,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        String mode = callback.getState() == null
                ? null
                : redis.opsForValue().get(STATE_PREFIX + callback.getState());
        if (mode == null) {
            redirectFrontend(response, "error");
            return;
        }
        redis.delete(STATE_PREFIX + callback.getState());
        AuthResponse<AuthUser> authResponse = githubRequest().login(callback);
        if (!authResponse.ok() || authResponse.getData() == null) {
            redirectFrontend(response, "error");
            return;
        }
        try {
            if (mode.startsWith("bind:")) {
                Integer userId = Integer.valueOf(mode.substring("bind:".length()));
                githubOAuthService.bind(userId, authResponse.getData());
                redirectFrontend(response, "bound");
                return;
            }
            GithubLoginResult result = githubOAuthService.loginOrRegister(
                    authResponse.getData(), deviceName(request), clientIp(request));
            response.addHeader(HttpHeaders.SET_COOKIE,
                    refreshCookie(result.getTokenPair().getRefreshToken()).toString());
            redirectFrontend(response, "success");
        } catch (Exception e) {
            redirectFrontend(response, "error");
        }
    }

    @DeleteMapping("/github/bind")
    @ResponseBody
    public ResponseEntity<CustomResponse> unbindGithub() {
        try {
            githubOAuthService.unbind(currentUser.getUserId());
            return ResponseEntity.ok(new CustomResponse(200, "GitHub account unlinked", null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(new CustomResponse(409, e.getMessage(), null));
        }
    }

    @GetMapping("/account/status")
    @ResponseBody
    public ResponseEntity<CustomResponse> accountStatus() {
        User user = githubOAuthService.getAccount(currentUser.getUserId());
        if (user == null) {
            return ResponseEntity.status(404).body(new CustomResponse(404, "Account does not exist", null));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("githubLinked", StringUtils.hasText(user.getGithubId()));
        data.put("githubLogin", user.getGithubLogin());
        data.put("passwordInitialized", !Integer.valueOf(0).equals(user.getPasswordInitialized()));
        data.put("role", user.getRole());
        return ResponseEntity.ok(new CustomResponse(200, "OK", data));
    }

    private void redirectToGithub(HttpServletResponse response, String mode) throws IOException {
        AuthProperties.ProviderConfig github = properties.getType().getGithub();
        if (!StringUtils.hasText(github.getClientId()) || !StringUtils.hasText(github.getClientSecret())) {
            response.sendError(503, "GitHub OAuth is not configured");
            return;
        }
        String state = AuthStateUtils.createState();
        redis.opsForValue().set(STATE_PREFIX + state, mode, 5, TimeUnit.MINUTES);
        response.sendRedirect(githubRequest().authorize(state));
    }

    private AuthRequest githubRequest() {
        AuthProperties.ProviderConfig github = properties.getType().getGithub();
        return new AuthGithubRequest(AuthConfig.builder()
                .clientId(github.getClientId())
                .clientSecret(github.getClientSecret())
                .redirectUri(github.getRedirectUri())
                .build());
    }

    private void redirectFrontend(HttpServletResponse response, String result) throws IOException {
        response.sendRedirect(properties.getFrontendRedirectUri() + "?github=" + result);
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(UserAccountController.REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(30))
                .build();
    }

    private String deviceName(HttpServletRequest request) {
        String explicit = request.getHeader("X-Device-Name");
        return StringUtils.hasText(explicit) ? explicit : request.getHeader("User-Agent");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
