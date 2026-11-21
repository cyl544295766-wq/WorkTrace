package com.worktrace.auth.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.worktrace.auth.dto.LoginRequest;
import com.worktrace.auth.dto.LoginUser;
import com.worktrace.auth.dto.RegisterReq;
import com.worktrace.auth.entity.SysUser;
import com.worktrace.auth.mapper.SysUserMapper;
import com.worktrace.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * POST /api/auth/login：校验用户名密码，成功后轮换 Session ID 并写入登录态。
     * 认证失败不创建/不改写任何会话（无登录态写入）。
     */
    @PostMapping("/login")
    public Result<LoginUser> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.username()));

        if (user == null || !matches(req.password(), user.getPassword())) {
            return Result.fail(Result.UNAUTHORIZED, "用户名或密码错误"); // 失败不写登录态、不动会话
        }
        // 历史明文密码：登录成功后就地升级为 BCrypt（消除明文存储；比对兼容分支保留）
        if (!isBcrypt(user.getPassword())) {
            SysUser upd = new SysUser();
            upd.setId(user.getId());
            upd.setPassword(passwordEncoder.encode(req.password()));
            sysUserMapper.updateById(upd);
        }

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getNickname());
        writeLoginUser(request, loginUser);
        return Result.ok(loginUser);
    }

    /**
     * POST /api/auth/register：注册新用户并自动登录。
     *
     * <p>密码一律 BCrypt 哈希落库；用户名唯一键 {@code uk_sys_user_username} 兜底并发注册。
     * 数据隔离不依赖本接口：sys_user 之外的业务数据全部按会话 userId 过滤，新用户天然只见自己的数据。
     * 注册失败不创建/不改写任何会话；成功路径与 login 相同：先轮换 Session ID 再写入登录态。
     */
    @PostMapping("/register")
    public Result<LoginUser> register(@Valid @RequestBody RegisterReq req, HttpServletRequest request) {
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

        // 注册即登录：与 login 成功分支同款轮换+写入
        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getNickname());
        writeLoginUser(request, loginUser);
        return Result.ok(loginUser);
    }

    /** GET /api/auth/info：从 HttpSession 读取当前登录用户（未登录已被拦截器 401） */
    @GetMapping("/info")
    public Result<LoginUser> info(HttpSession session) {
        return Result.ok((LoginUser) session.getAttribute(LoginUser.SESSION_KEY));
    }

    /**
     * 会话固定（Session Fixation）防护：把登录态写入<b>新建</b>会话而非沿用请求带来的旧会话。
     *
     * <p>流程：先失效请求携带的旧会话（Spring Session JDBC 立即删 SPRING_SESSION 行，
     * 攻击者预先布置/已知的旧 Session Cookie 随之失效），再创建全新会话写入登录用户信息；
     * 响应由 CookieHttpSessionIdResolver 下发新 SESSION Cookie。会话 ID 在认证成功前后
     * 必然变化，固定在旧 ID 上的登录态无从谈起。失败路径不经此方法，不产生任何会话写入。
     */
    private void writeLoginUser(HttpServletRequest request, LoginUser loginUser) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true).setAttribute(LoginUser.SESSION_KEY, loginUser);
    }

    /**
     * POST /api/auth/logout：退出登录——使当前 Spring Session 立即失效。
     *
     * <p>Spring Session JDBC 下 {@code invalidate()} 会删除 SPRING_SESSION 表中该会话的行，
     * 旧 Session Cookie 复访受保护接口立即 401；SessionRepositoryFilter 还会随响应下发
     * Max-Age=0 的空同名 Cookie 清除浏览器侧会话（expireSession）。只失效当前会话，
     * 其他用户的会话互不影响。拦截器已保证到达这里的请求必带有效会话。
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpSession session) {
        session.invalidate();
        return Result.ok();
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
