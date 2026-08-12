package com.cixingji.backend.pojo.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@Data
@AllArgsConstructor
public class AdminUserView {
    private Integer uid;
    private String username;
    private String nickname;
    private Integer role;
    private Integer state;
    private boolean githubLinked;
    private Date createDate;
}
