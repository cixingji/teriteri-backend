package com.cixingji.backend.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cixingji.backend.mapper.FavoriteMapper;
import com.cixingji.backend.mapper.MsgUnreadMapper;
import com.cixingji.backend.mapper.UserMapper;
import com.cixingji.backend.pojo.dto.auth.AuthTokenPair;
import com.cixingji.backend.pojo.dto.auth.GithubLoginResult;
import com.cixingji.backend.pojo.entity.Favorite;
import com.cixingji.backend.pojo.entity.MsgUnread;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.utils.ESUtil;
import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.model.AuthUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

@Service
@Slf4j
public class GithubOAuthService {

    private final UserMapper userMapper;
    private final MsgUnreadMapper msgUnreadMapper;
    private final FavoriteMapper favoriteMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final ESUtil esUtil;
    private final SecureRandom secureRandom = new SecureRandom();

    public GithubOAuthService(UserMapper userMapper,
                              MsgUnreadMapper msgUnreadMapper,
                              FavoriteMapper favoriteMapper,
                              PasswordEncoder passwordEncoder,
                              AuthSessionService authSessionService,
                              ESUtil esUtil) {
        this.userMapper = userMapper;
        this.msgUnreadMapper = msgUnreadMapper;
        this.favoriteMapper = favoriteMapper;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
        this.esUtil = esUtil;
    }

    @Transactional
    public GithubLoginResult loginOrRegister(AuthUser githubUser, String deviceName, String ipAddress) {
        String githubId = requireGithubId(githubUser);
        User user = findByGithubId(githubId);
        if (user == null) {
            user = createGithubUser(githubUser);
        }
        if (!Integer.valueOf(0).equals(user.getState())) {
            throw new IllegalStateException("Account is unavailable");
        }
        AuthTokenPair pair = authSessionService.createSession(user.getUid(), "user", deviceName, ipAddress);
        return new GithubLoginResult(user, pair);
    }

    @Transactional
    public void bind(Integer userId, AuthUser githubUser) {
        String githubId = requireGithubId(githubUser);
        User linked = findByGithubId(githubId);
        if (linked != null && !userId.equals(linked.getUid())) {
            throw new IllegalStateException("This GitHub account is already linked");
        }
        UpdateWrapper<User> update = new UpdateWrapper<>();
        update.eq("uid", userId)
                .set("github_id", githubId)
                .set("github_login", safeGithubLogin(githubUser));
        userMapper.update(null, update);
    }

    @Transactional
    public void unbind(Integer userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("Account does not exist");
        }
        if (!Integer.valueOf(1).equals(user.getPasswordInitialized())) {
            throw new IllegalStateException("Set a password before unlinking GitHub");
        }
        UpdateWrapper<User> update = new UpdateWrapper<>();
        update.eq("uid", userId).set("github_id", null).set("github_login", null);
        userMapper.update(null, update);
    }

    public User getAccount(Integer userId) {
        return userMapper.selectById(userId);
    }

    private User findByGithubId(String githubId) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("github_id", githubId));
    }

    private User createGithubUser(AuthUser githubUser) {
        String githubId = requireGithubId(githubUser);
        String login = safeGithubLogin(githubUser);
        User user = new User();
        user.setUsername(uniqueUsername(login, githubId));
        user.setPassword(passwordEncoder.encode(randomPassword()));
        user.setNickname(uniqueNickname(githubUser.getNickname(), login, githubId));
        user.setAvatar(githubUser.getAvatar());
        user.setBackground("");
        user.setGender(2);
        user.setDescription("通过 GitHub 加入芙影视界");
        user.setExp(0);
        user.setCoin(0D);
        user.setVip(0);
        user.setState(0);
        user.setRole(0);
        user.setAuth(0);
        user.setCreateDate(new Date());
        user.setGithubId(githubId);
        user.setGithubLogin(login);
        user.setPasswordInitialized(0);
        userMapper.insert(user);
        msgUnreadMapper.insert(new MsgUnread(user.getUid(), 0, 0, 0, 0, 0, 0));
        favoriteMapper.insert(new Favorite(null, user.getUid(), 1, 1, null, "默认收藏夹", "", 0, null));
        try {
            esUtil.addUser(user);
        } catch (Exception e) {
            log.warn("Could not add GitHub user {} to Elasticsearch", user.getUid(), e);
        }
        return user;
    }

    private String uniqueUsername(String login, String githubId) {
        String normalized = login.replaceAll("[^A-Za-z0-9_]", "_");
        if (normalized.isEmpty()) {
            normalized = githubId;
        }
        String base = "github_" + normalized;
        if (base.length() > 42) {
            base = base.substring(0, 42);
        }
        String value = base;
        int suffix = 1;
        while (userMapper.selectCount(new QueryWrapper<User>().eq("username", value)) > 0) {
            value = base + "_" + suffix++;
        }
        return value;
    }

    private String uniqueNickname(String nickname, String login, String githubId) {
        String base = StringUtils.hasText(nickname) ? nickname.trim() : login;
        if (!StringUtils.hasText(base)) {
            base = "GitHub用户" + githubId;
        }
        if (base.length() > 26) {
            base = base.substring(0, 26);
        }
        String value = base;
        int suffix = 1;
        while (userMapper.selectCount(new QueryWrapper<User>().eq("nickname", value)) > 0) {
            value = base + suffix++;
        }
        return value;
    }

    private String safeGithubLogin(AuthUser githubUser) {
        return StringUtils.hasText(githubUser.getUsername()) ? githubUser.getUsername() : githubUser.getUuid();
    }

    private String requireGithubId(AuthUser githubUser) {
        if (githubUser == null || !StringUtils.hasText(githubUser.getUuid())) {
            throw new IllegalStateException("GitHub did not return a user id");
        }
        return githubUser.getUuid();
    }

    private String randomPassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
