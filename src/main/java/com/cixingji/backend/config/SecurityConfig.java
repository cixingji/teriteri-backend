package com.cixingji.backend.config;

import com.cixingji.backend.config.filter.JwtAuthenticationTokenFilter;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(UserDetailsService userDetailsService,
                          JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter,
                          ObjectMapper objectMapper) {
        this.userDetailsService = userDetailsService;
        this.jwtAuthenticationTokenFilter = jwtAuthenticationTokenFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                String username = authentication.getName();
                String password = authentication.getCredentials().toString();
                UserDetails loginUser = userDetailsService.loadUserByUsername(username);
                if (Objects.isNull(loginUser) || !passwordEncoder().matches(password, loginUser.getPassword())) {
                    throw new BadCredentialsException("Invalid username or password");
                }
                return new UsernamePasswordAuthenticationToken(loginUser, password, loginUser.getAuthorities());
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return authentication.equals(UsernamePasswordAuthenticationToken.class);
            }
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .exceptionHandling()
                .authenticationEntryPoint((request, response, exception) -> writeError(response, 401, "Authentication required"))
                .accessDeniedHandler((request, response, exception) -> writeError(response, 403, "Access denied")).and()
                .authorizeRequests(authorize -> authorize
                        .antMatchers(HttpMethod.OPTIONS).permitAll()
                        .antMatchers(
                                "/druid/**", "/favicon.ico",
                                "/user/account/register",
                                "/user/account/login",
                                "/admin/account/login",
                                "/auth/refresh",
                                "/oauth/render/github",
                                "/oauth/callback/github",
                                "/category/getall",
                                "/video/random/visitor",
                                "/video/cumulative/visitor",
                                "/video/getone",
                                "/media/covers/**",
                                "/media/videos/**",
                                "/ws/danmu/**",
                                "/danmu-list/**",
                                "/msg/chat/outline",
                                "/video/play/visitor",
                                "/favorite/get-all/visitor",
                                "/search/**",
                                "/comment/get",
                                "/comment/reply/get-more",
                                "/comment/get-up-like",
                                "/video/summary/callback/xfyun",
                                "/user/info/get-one",
                                "/video/user-works-count",
                                "/video/user-works",
                                "/video/user-love",
                                "/video/user-collect"
                        ).permitAll()
                        .antMatchers("/admin/**", "/review/**", "/video/change/status")
                        .hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private void writeError(javax.servlet.http.HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new CustomResponse(status, message, null));
    }
}
