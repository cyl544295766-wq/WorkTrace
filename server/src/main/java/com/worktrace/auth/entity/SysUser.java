package com.worktrace.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录演示用户，对应表 sys_user。
 * createTime → create_time 由 MyBatis-Plus 驼峰转下划线自动映射。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 库中为 BCrypt 哈希（演示数据可为明文），永不写入日志/响应 */
    private String password;

    private String nickname;

    private LocalDateTime createTime;
}
