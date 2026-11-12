package com.worktrace.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（POST /api/auth/register）。
 * 密码一律 BCrypt 哈希后落库；nickname 可选（空则默认=username）。
 */
public record RegisterReq(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "用户名为 3-32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度 6-32 位")
        String password,

        @Size(max = 16, message = "昵称最长 16 字")
        String nickname
) {
}
