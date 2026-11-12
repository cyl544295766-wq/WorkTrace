package com.worktrace.auth.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.worktrace.auth.dto.LoginRequest;
import com.worktrace.auth.dto.LoginUser;
import com.worktrace.auth.dto.RegisterReq;
import com.worktrace.auth.entity.SysUser;
import com.worktrace.auth.mapper.SysUserMapper;
import com.worktrace.common.api.Result;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

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
        // 历史明文密码：登录成功后就地升级为 BCrypt（消除明文存储；比对兼容分支保留）
        if (!isBcrypt(user.getPassword())) {
            SysUser upd = new SysUser();
            upd.setId(user.getId());
            upd.setPassword(passwordEncoder.encode(req.password()));
            sysUserMapper.updateById(upd);
        }

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getNickname());
        session.setAttribute(LoginUser.SESSION_KEY, loginUser);
        return Result.ok(loginUser);
    }

    /**
     * POST /api/auth/register：注册新用户并自动登录。
     *
     * <p>密码一律 BCrypt 哈希落库；用户名唯一键 {@code uk_sys_user_username} 兜底并发注册。
     * 数据隔离不依赖本接口：sys_user 之外的业务数据全部按会话 userId 过滤，新用户天然只见自己的数据。
     */
    @PostMapping("/register")
    public Result<LoginUser> register(@Valid @RequestBody RegisterReq req, HttpSession session) {
        String username = req.username();
        if (sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)) != null) {
            return Result.fail(Result.BAD_REQUEST, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.password())); // 新用户一律 BCrypt
        String nickname = req.nickname() == null || req.nickname().isBlank()
                ? username : req.nickname().trim();
        user.setNickname(nickname);
        user.setCreateTime(LocalDateTime.now());
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册撞唯一键：与查重同文案
            return Result.fail(Result.BAD_REQUEST, "用户名已存在");
        }

        // 注册即登录：与 login 成功分支同款会话写入
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
        if (isBcrypt(stored)) {
            return passwordEncoder.matches(raw, stored);
        }
        return stored.equals(raw); // 历史明文直接比对；登录成功后由 login 就地升级为 BCrypt
    }

    /** 存储值是否已是 BCrypt 哈希 */
    private boolean isBcrypt(String stored) {
        return stored != null
                && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"));
    }
}
