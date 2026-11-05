package com.worktrace.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交注解：标记在 Controller 方法上。
 *
 * <p>切面按 <b>当前登录用户 + 业务类型</b> 加锁：锁定期间同用户同业务的重复请求
 * 抛出 {@code BizException(429, "请勿频繁操作")}。
 *
 * <pre>
 * &#64;Idempotent(key = "report:generate", expireSeconds = 10)
 * &#64;PostMapping("/generate")
 * public Result&lt;BizReport&gt; generate(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 业务类型标识：不同接口用不同 key，互不干扰 */
    String key();

    /** 锁兜底过期秒数：即使业务线程异常未主动释放，锁也会自动失效 */
    int expireSeconds() default 10;
}
