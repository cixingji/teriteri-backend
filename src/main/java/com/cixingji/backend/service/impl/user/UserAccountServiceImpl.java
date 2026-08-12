package com.cixingji.backend.service.impl.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.im.IMServer;
import com.cixingji.backend.mapper.FavoriteMapper;
import com.cixingji.backend.mapper.MsgUnreadMapper;
import com.cixingji.backend.mapper.UserMapper;
import com.cixingji.backend.pojo.dto.UserDTO;
import com.cixingji.backend.pojo.dto.auth.AuthTokenPair;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.pojo.entity.Favorite;
import com.cixingji.backend.pojo.entity.MsgUnread;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.service.auth.AuthSessionService;
import com.cixingji.backend.service.auth.LoginAttemptService;
import com.cixingji.backend.service.user.UserAccountService;
import com.cixingji.backend.service.user.UserService;
import com.cixingji.backend.service.utils.CurrentUser;
import com.cixingji.backend.utils.ESUtil;
import com.cixingji.backend.utils.RedisUtil;
import io.netty.channel.Channel;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class UserAccountServiceImpl implements UserAccountService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final MsgUnreadMapper msgUnreadMapper;
    private final FavoriteMapper favoriteMapper;
    private final RedisUtil redisUtil;
    private final ESUtil esUtil;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationProvider authenticationProvider;
    private final AuthSessionService authSessionService;
    private final LoginAttemptService loginAttemptService;

    public UserAccountServiceImpl(UserService userService,
                                  UserMapper userMapper,
                                  MsgUnreadMapper msgUnreadMapper,
                                  FavoriteMapper favoriteMapper,
                                  RedisUtil redisUtil,
                                  ESUtil esUtil,
                                  CurrentUser currentUser,
                                  PasswordEncoder passwordEncoder,
                                  AuthenticationProvider authenticationProvider,
                                  AuthSessionService authSessionService,
                                  LoginAttemptService loginAttemptService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.msgUnreadMapper = msgUnreadMapper;
        this.favoriteMapper = favoriteMapper;
        this.redisUtil = redisUtil;
        this.esUtil = esUtil;
        this.currentUser = currentUser;
        this.passwordEncoder = passwordEncoder;
        this.authenticationProvider = authenticationProvider;
        this.authSessionService = authSessionService;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    @Transactional
    public CustomResponse register(String username, String password, String confirmedPassword,
                                   String deviceName, String ipAddress) throws IOException {
        CustomResponse validation = validateCredentials(username, password, confirmedPassword);
        if (validation != null) {
            return validation;
        }
        username = username.trim();
        QueryWrapper<User> existingQuery = new QueryWrapper<>();
        existingQuery.eq("username", username).ne("state", 2);
        if (userMapper.selectOne(existingQuery) != null) {
            return error(409, "Username already exists");
        }

        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(password));
        newUser.setNickname(uniqueNickname("用户_" + System.currentTimeMillis()));
        newUser.setAvatar("https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png");
        newUser.setBackground("https://tinypic.host/images/2023/11/15/69PB2Q5W9D2U7L.png");
        newUser.setGender(2);
        newUser.setDescription("这个人很懒，什么都没有留下");
        newUser.setExp(0);
        newUser.setCoin(0D);
        newUser.setVip(0);
        newUser.setState(0);
        newUser.setRole(0);
        newUser.setAuth(0);
        newUser.setCreateDate(new Date());
        newUser.setPasswordInitialized(1);
        userMapper.insert(newUser);
        msgUnreadMapper.insert(new MsgUnread(newUser.getUid(), 0, 0, 0, 0, 0, 0));
        favoriteMapper.insert(new Favorite(null, newUser.getUid(), 1, 1, null, "默认收藏夹", "", 0, null));
        esUtil.addUser(newUser);

        CustomResponse response = login(username, password, deviceName, ipAddress);
        if (response.getCode() == 200) {
            response.setMessage("Registration successful");
        }
        return response;
    }

    @Override
    public CustomResponse login(String username, String password, String deviceName, String ipAddress) {
        return authenticate(username, password, "user", deviceName, ipAddress);
    }

    @Override
    public CustomResponse adminLogin(String username, String password, String deviceName, String ipAddress) {
        return authenticate(username, password, "admin", deviceName, ipAddress);
    }

    private CustomResponse authenticate(String username, String password, String scope,
                                        String deviceName, String ipAddress) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (loginAttemptService.isLocked(normalizedUsername, ipAddress)) {
            return error(423, "Too many failed attempts; try again in 15 minutes");
        }
        Authentication authentication;
        try {
            authentication = authenticationProvider.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedUsername, password == null ? "" : password)
            );
        } catch (Exception e) {
            loginAttemptService.recordFailure(normalizedUsername, ipAddress);
            return error(401, "Invalid username or password");
        }
        loginAttemptService.reset(normalizedUsername, ipAddress);
        User user = ((UserDetailsImpl) authentication.getPrincipal()).getUser();
        if (Integer.valueOf(1).equals(user.getState())) {
            return error(423, "Account is locked");
        }
        if (!Integer.valueOf(0).equals(user.getState())) {
            return error(401, "Account is unavailable");
        }
        if ("admin".equals(scope) && Integer.valueOf(0).equals(user.getRole())) {
            return error(403, "Administrator access required");
        }
        redisUtil.setExObjectValue("user:" + user.getUid(), user);
        AuthTokenPair pair = authSessionService.createSession(user.getUid(), scope, deviceName, ipAddress);
        return tokenResponse(user, pair, "Login successful");
    }

    @Override
    public CustomResponse personalInfo() {
        UserDTO user = userService.getUserById(currentUser.getUserId());
        if (user == null) {
            return error(404, "Account does not exist");
        }
        return new CustomResponse(200, "OK", user);
    }

    @Override
    public CustomResponse adminPersonalInfo() {
        User user = userMapper.selectById(currentUser.getUserId());
        if (user == null) {
            return error(404, "Account does not exist");
        }
        if (Integer.valueOf(0).equals(user.getRole())) {
            return error(403, "Administrator access required");
        }
        return new CustomResponse(200, "OK", toUserDTO(user));
    }

    @Override
    public void logout() {
        authSessionService.revokeSession(currentUser.getSessionId());
    }

    @Override
    public void adminLogout() {
        logout();
    }

    @Override
    public void logoutAll() {
        Integer userId = currentUser.getUserId();
        authSessionService.revokeAll(userId);
        redisUtil.delMember("login_member", userId);
        redisUtil.deleteKeysWithPrefix("whisper:" + userId + ":");
        closeUserChannels(userId);
    }

    @Override
    public CustomResponse updatePassword(String currentPassword, String newPassword) {
        CustomResponse passwordValidation = validateNewPassword(newPassword);
        if (passwordValidation != null) {
            return passwordValidation;
        }
        User user = currentPrincipal();
        try {
            authenticationProvider.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), currentPassword == null ? "" : currentPassword)
            );
        } catch (Exception e) {
            return error(401, "Current password is incorrect");
        }
        if (Objects.equals(currentPassword, newPassword)) {
            return error(400, "New password must differ from the current password");
        }
        updatePassword(user, newPassword);
        return new CustomResponse(200, "Password updated; please sign in again", null);
    }

    @Override
    public CustomResponse setInitialPassword(String newPassword) {
        CustomResponse passwordValidation = validateNewPassword(newPassword);
        if (passwordValidation != null) {
            return passwordValidation;
        }
        User user = currentPrincipal();
        if (Integer.valueOf(1).equals(user.getPasswordInitialized())) {
            return error(409, "Password is already configured");
        }
        updatePassword(user, newPassword);
        return new CustomResponse(200, "Password configured; please sign in again", null);
    }

    private void updatePassword(User user, String newPassword) {
        UpdateWrapper<User> update = new UpdateWrapper<>();
        update.eq("uid", user.getUid())
                .set("password", passwordEncoder.encode(newPassword))
                .set("password_initialized", 1);
        userMapper.update(null, update);
        authSessionService.revokeAll(user.getUid());
        closeUserChannels(user.getUid());
    }

    private User currentPrincipal() {
        UsernamePasswordAuthenticationToken authentication =
                (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return ((UserDetailsImpl) authentication.getPrincipal()).getUser();
    }

    private CustomResponse tokenResponse(User user, AuthTokenPair pair, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", pair.getAccessToken());
        data.put("accessToken", pair.getAccessToken());
        data.put("refreshToken", pair.getRefreshToken());
        data.put("accessTokenExpiresIn", pair.getAccessTokenExpiresInSeconds());
        data.put("sessionId", pair.getSessionId());
        data.put("user", toUserDTO(user));
        data.put("role", user.getRole());
        data.put("githubLinked", user.getGithubId() != null);
        data.put("passwordInitialized", !Integer.valueOf(0).equals(user.getPasswordInitialized()));
        return new CustomResponse(200, message, data);
    }

    private UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setUid(user.getUid());
        dto.setNickname(user.getNickname());
        dto.setAvatar_url(user.getAvatar());
        dto.setBg_url(user.getBackground());
        dto.setGender(user.getGender());
        dto.setDescription(user.getDescription());
        dto.setExp(user.getExp());
        dto.setCoin(user.getCoin());
        dto.setVip(user.getVip());
        dto.setState(user.getState());
        dto.setAuth(user.getAuth());
        dto.setAuthMsg(user.getAuthMsg());
        return dto;
    }

    private CustomResponse validateCredentials(String username, String password, String confirmedPassword) {
        if (username == null || username.trim().isEmpty()) {
            return error(400, "Username is required");
        }
        if (username.trim().length() > 50) {
            return error(400, "Username must not exceed 50 characters");
        }
        if (password == null || password.isEmpty()) {
            return error(400, "Password is required");
        }
        if (password.length() > 72) {
            return error(400, "Password must not exceed 72 characters");
        }
        if (!password.equals(confirmedPassword)) {
            return error(400, "Passwords do not match");
        }
        return null;
    }

    private CustomResponse validateNewPassword(String password) {
        if (password == null || password.isEmpty()) {
            return error(400, "Password is required");
        }
        if (password.length() > 72) {
            return error(400, "Password must not exceed 72 characters");
        }
        return null;
    }

    private String uniqueNickname(String candidate) {
        String base = candidate.length() > 28 ? candidate.substring(0, 28) : candidate;
        String value = base;
        int suffix = 1;
        while (userMapper.selectCount(new QueryWrapper<User>().eq("nickname", value)) > 0) {
            value = base + suffix++;
        }
        return value;
    }

    private void closeUserChannels(Integer userId) {
        Set<Channel> channels = IMServer.userChannel.remove(userId);
        if (channels == null) {
            return;
        }
        for (Channel channel : channels) {
            channel.close();
        }
    }

    private CustomResponse error(int code, String message) {
        return new CustomResponse(code, message, null);
    }
}
