package com.cixingji.backend.pojo.dto.auth;

import com.cixingji.backend.pojo.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GithubLoginResult {
    private User user;
    private AuthTokenPair tokenPair;
}
