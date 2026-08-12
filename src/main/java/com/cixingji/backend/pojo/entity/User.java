package com.cixingji.backend.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Table;
import java.io.Serializable;
import java.util.Date;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Table(name = "user") // 标明对应的数据库表名
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name="uid")
    @TableId
    private Integer uid;

    @Column(name="username")
    private String username;

    @Column(name="password")
    private String password;

    @Column(name="nickname")
    private String nickname;

    @Column(name="avatar")
    private String avatar;

    @Column(name="background")
    private String background;

    @Column(name="gender")
    private Integer gender; // 性别，0女性 1男性 2无性别，默认2

    @Column(name="description")
    private String description;

    @Column(name="exp")
    private Integer exp;    // 经验值

    @Column(name="coin")
    private Double coin;    // 硬币数

    @Column(name="vip")
    private Integer vip;    // 会员类型

    @Column(name="state")
    private Integer state;  // 状态

    @Column(name="role")
    private Integer role;   // 角色类型

    @Column(name="auth")
    private Integer auth;   // 官方认证

    @Column(name="auth_msg") // 数据库字段名是 auth_msg
    private String authMsg; // Java字段名是 authMsg

    @Column(name="create_date") // 数据库字段名是 create_date
    private Date createDate;

    @Column(name="delete_date") // 数据库字段名是 delete_date
    private Date deleteDate;


    @Override
    public String toString() {
        return "User{" +
                "uid=" + uid +
                ", username='" + username + '\'' +
                ", nickname='" + nickname + '\'' +
                ", avatar='" + avatar + '\'' +
                ", background='" + background + '\'' +
                ", gender=" + gender +
                ", description='" + description + '\'' +
                ", exp=" + exp +
                ", coin=" + coin +
                ", vip=" + vip +
                ", state=" + state +
                ", role=" + role +
                ", auth=" + auth +
                ", authMsg='" + authMsg + '\'' +
                ", createDate=" + createDate +
                ", deleteDate=" + deleteDate +
                '}';
    }
}