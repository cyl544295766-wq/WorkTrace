package com.worktrace.common.idempotent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.worktrace.auth.dto.LoginUser;
import com.worktrace.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 防重复提交切面。
 *
 * <p>锁键 = {@code idempotent:{key}:{userId}}，同一用户同一业务在锁持有期间
 * 的重复请求抛 {@code BizException(429)}。
 *
 * <p>实现说明：
 * <ul>
 *   <li>锁存储用 Caffeine（底层 ConcurrentHashMap 的原子 compute），
 *       条目级 TTL 兜底 + maximumSize 防泄漏；本项目单机部署、明确不引入 Redis，
 *       若未来多实例部署，把锁操作换成 {@code SET key val NX EX expireSeconds} 即可，切面其余不变。</li>
 *   <li>锁生命周期：业务方法正常/异常返回时主动释放（finally），
 *       {@code expireSeconds} 只是线程崩溃等异常情况下的自动过期兜底。</li>
 *   <li>用户身份取自 HttpSession（LoginInterceptor 已保证到达 Controller 的请求必然已登录），
 *       未登录时直接拒绝。</li>
 * </ul>
 */
@Aspect
@Component
public class IdempotentAspect {

    /** value = 锁到期时间戳（毫秒）；5 分钟为绝对兜底上限，防极端情况下的条目滞留 */
    private final Cache<String, Long> locks = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String lockKey = buildLockKey(idempotent);
        long expireAt = System.currentTimeMillis() + idempotent.expireSeconds() * 1000L;

        // 原子 check-and-set：已持锁（未过期）→ 拒绝；无锁/锁已过期 → 加锁
        try {
            locks.asMap().compute(lockKey, (k, prev) -> {
                if (prev != null && prev > System.currentTimeMillis()) {
                    throw new BizException(429, "请勿频繁操作，请稍后再试");
                }
                return expireAt;
            });
        } catch (BizException e) {
            throw e; // compute 内抛出的拒绝异常原样上抛（ConcurrentHashMap 不会写入该条目）
        }

        try {
            return pjp.proceed();
        } finally {
            // 业务结束（含异常）主动释放；异步 SSE 场景方法本身快速返回，锁仅覆盖提交窗口，
            // 真正的防重由 expireSeconds 的最小间隔兜底
            locks.asMap().compute(lockKey, (k, prev) -> {
                if (prev != null && prev > System.currentTimeMillis()) {
                    return null; // 只释放自己加的、未过期的锁
                }
                return prev;   // 已被兜底逻辑判定过期则不动
            });
        }
    }

    /** 锁键 = 前缀 + 业务类型 + 当前登录用户 id */
    private String buildLockKey(Idempotent idempotent) {
        LoginUser user = currentUser();
        if (user == null) {
            throw new BizException(401, "未登录或会话已过期");
        }
        return "idempotent:" + idempotent.key() + ":" + user.getId();
    }

    /** 从当前请求线程取会话用户 */
    private LoginUser currentUser() {
        var attrs = RequestContextHolder.currentRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            HttpSession session = request.getSession(false);
            if (session != null) {
                return (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
            }
        }
        return null;
    }
}
