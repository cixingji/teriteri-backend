package com.cixingji.backend.config.filter;

import com.cixingji.backend.mapper.UserMapper;
import com.cixingji.backend.pojo.dto.auth.AuthSession;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.service.auth.AuthSessionService;
import com.cixingji.backend.service.impl.user.UserDetailsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {

    private final AuthSessionService authSessionService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationTokenFilter(AuthSessionService authSessionService,
                                        UserMapper userMapper,
                                        ObjectMapper objectMapper) {
        this.authSessionService = authSessionService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = header.substring(7);
            AuthSession session = authSessionService.validateAccessToken(token);
            if (session == null) {
                writeError(response, 401, "Authentication required");
                return;
            }
            User user = userMapper.selectById(session.getUserId());
            if (user == null || Integer.valueOf(2).equals(user.getState())) {
                authSessionService.revokeAll(session.getUserId());
                writeError(response, 401, "Account does not exist");
                return;
            }
            if (Integer.valueOf(1).equals(user.getState())) {
                authSessionService.revokeAll(session.getUserId());
                writeError(response, 423, "Account is locked");
                return;
            }
            if ("admin".equals(session.getScope()) && Integer.valueOf(0).equals(user.getRole())) {
                authSessionService.revokeSession(session.getSessionId());
                writeError(response, 403, "Administrator access required");
                return;
            }

            UserDetailsImpl principal = new UserDetailsImpl(user, session.getScope());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
            );
            authentication.setDetails(session.getSessionId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (DataAccessException e) {
            writeError(response, 503, "Authentication service is temporarily unavailable");
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new CustomResponse(status, message, null));
    }
}
