package com.worktrace.auth.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.worktrace.auth.dto.LoginRequest;
import com.worktrace.auth.dto.LoginUser;
import com.worktrace.auth.entity.SysUser;
import com.worktrace.auth.mapper.SysUserMapper;
import com.worktrace.common.api.Result;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录与当前用户信息。
 * 会话由 Spring Session JDBC 管理：HttpSession 的写操作自动落库 SPRING_SESSION 表，
 * 重启应用/换实例只要共享同一数据库，会话不丢失。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysUserMapper sysUserMapper;

    /** 仅用于 BCrypt 格式的存量密码；演示明文数据走"直接比对"分支 */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /** POST /api/auth/login：校验用户名密码，成功后将用户信息存入 HttpSession */
    @PostMapping("/login")
    public Result<LoginUser> login(@Valid @RequestBody LoginRequest req, HttpSession session) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.username()));

        if (user == null || !matches(req.password(), user.getPassword())) {
            return Result.fail(Result.UNAUTHORIZED, "用户名或密码错误");
        }

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getNickname());
        session.setAttribute(LoginUser.SESSION_KEY, loginUser);
        return Result.ok(loginUser);
    }

    /** GET /api/auth/info：从 HttpSession 读取当前登录用户（未登录已被拦截器 401） */
    @GetMapping("/info")
    public Result<LoginUser> info(HttpSession session) {
        return Result.ok((LoginUser) session.getAttribute(LoginUser.SESSION_KEY));
    }

    private boolean matches(String raw, String stored) {
        if (stored == null) {
            return false;
        }
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return passwordEncoder.matches(raw, stored);
        }
        return stored.equals(raw); // 简化模拟：明文直接比对
    }
}
