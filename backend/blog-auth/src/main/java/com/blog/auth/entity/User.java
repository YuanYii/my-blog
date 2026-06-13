package com.blog.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员账号
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String avatar;
    private String bio;
    private String location;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
