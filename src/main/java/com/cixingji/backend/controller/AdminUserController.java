package com.cixingji.backend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cixingji.backend.im.IMServer;
import com.cixingji.backend.mapper.UserMapper;
import com.cixingji.backend.pojo.dto.auth.AdminUserView;
import com.cixingji.backend.pojo.entity.CustomResponse;
import com.cixingji.backend.pojo.entity.User;
import com.cixingji.backend.service.auth.AuthSessionService;
import com.cixingji.backend.service.utils.CurrentUser;
import io.netty.channel.Channel;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserMapper userMapper;
    private final CurrentUser currentUser;
    private final AuthSessionService authSessionService;

    public AdminUserController(UserMapper userMapper,
                               CurrentUser currentUser,
                               AuthSessionService authSessionService) {
        this.userMapper = userMapper;
        this.currentUser = currentUser;
        this.authSessionService = authSessionService;
    }

    @GetMapping
    public CustomResponse list(@RequestParam(defaultValue = "1") long page,
                               @RequestParam(defaultValue = "20") long size,
                               @RequestParam(required = false) String keyword) {
        long safeSize = Math.max(1, Math.min(size, 100));
        QueryWrapper<User> query = new QueryWrapper<User>().ne("state", 2).orderByDesc("uid");
        if (StringUtils.hasText(keyword)) {
            query.and(wrapper -> wrapper.like("username", keyword.trim())
                    .or().like("nickname", keyword.trim()));
        }
        Page<User> result = userMapper.selectPage(new Page<>(Math.max(1, page), safeSize), query);
        List<AdminUserView> records = new ArrayList<>();
        for (User user : result.getRecords()) {
            records.add(view(user));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("records", records);
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        return new CustomResponse(200, "OK", data);
    }

    @PatchMapping("/{userId}/state")
    public ResponseEntity<CustomResponse> updateState(@PathVariable Integer userId,
                                                      @RequestBody Map<String, Integer> body) {
        Integer state = body.get("state");
        if (state == null || (state != 0 && state != 1)) {
            return badRequest("state must be 0 or 1");
        }
        User operator = userMapper.selectById(currentUser.getUserId());
        User target = userMapper.selectById(userId);
        if (target == null) {
            return ResponseEntity.status(404).body(new CustomResponse(404, "User not found", null));
        }
        if (operator.getUid().equals(target.getUid())) {
            return badRequest("You cannot lock your own account");
        }
        if (target.getRole() >= operator.getRole()) {
            return ResponseEntity.status(403)
                    .body(new CustomResponse(403, "You cannot manage an equal or higher administrator", null));
        }
        User update = new User();
        update.setUid(userId);
        update.setState(state);
        userMapper.updateById(update);
        if (state == 1) {
            authSessionService.revokeAll(userId);
            closeChannels(userId);
        }
        return ResponseEntity.ok(new CustomResponse(200, state == 1 ? "User locked" : "User unlocked", null));
    }

    @PatchMapping("/{userId}/role")
    public ResponseEntity<CustomResponse> updateRole(@PathVariable Integer userId,
                                                     @RequestBody Map<String, Integer> body) {
        User operator = userMapper.selectById(currentUser.getUserId());
        if (operator == null || !Integer.valueOf(2).equals(operator.getRole())) {
            return ResponseEntity.status(403)
                    .body(new CustomResponse(403, "Super administrator access required", null));
        }
        Integer role = body.get("role");
        if (role == null || (role != 0 && role != 1)) {
            return badRequest("role must be 0 or 1");
        }
        if (operator.getUid().equals(userId)) {
            return badRequest("You cannot change your own role");
        }
        User target = userMapper.selectById(userId);
        if (target == null) {
            return ResponseEntity.status(404).body(new CustomResponse(404, "User not found", null));
        }
        if (Integer.valueOf(2).equals(target.getRole())) {
            return badRequest("A super administrator cannot be changed here");
        }
        User update = new User();
        update.setUid(userId);
        update.setRole(role);
        userMapper.updateById(update);
        authSessionService.revokeAll(userId);
        closeChannels(userId);
        return ResponseEntity.ok(new CustomResponse(200, "Role updated", null));
    }

    private AdminUserView view(User user) {
        return new AdminUserView(
                user.getUid(),
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getState(),
                StringUtils.hasText(user.getGithubId()),
                user.getCreateDate()
        );
    }

    private void closeChannels(Integer userId) {
        Set<Channel> channels = IMServer.userChannel.remove(userId);
        if (channels != null) {
            for (Channel channel : channels) {
                channel.close();
            }
        }
    }

    private ResponseEntity<CustomResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(new CustomResponse(400, message, null));
    }
}
