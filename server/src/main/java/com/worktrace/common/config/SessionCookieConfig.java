package com.worktrace.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.time.Duration;

/**
 * Spring Session 的 Cookie 属性与会话超时——这是<b>唯一生效</b>的配置点。
 *
 * <p>⚠️ 项目由 Spring Session 接管会话后，{@code server.servlet.session.*}（原生容器会话层）
 * 是死配置：SESSION Cookie 的属性实际来自这里的 {@link DefaultCookieSerializer}，
 * 会话有效期来自下面的 Repository 定制器，两者都不读 server.servlet 前缀。
 *
 * <p>当前部署为纯 HTTP（Nginx :8081 静态 + /api 反代 :8083，前端与接口同源同主机）：
 * <ul>
 *   <li>name=SESSION、path=/：与 Spring Session 默认一致，显式固定防漂移；</li>
 *   <li>httpOnly=true：防 XSS 读取会话 Cookie；</li>
 *   <li>secure=false：HTTP 下浏览器会丢弃带 Secure 的 Cookie（表现为"登录成功即掉线"且无报错）；
 *       显式固定为 false，不随 request.isSecure() 自动探测漂移；升 HTTPS 时需连同 HSTS 一起改（design.md §1.4）；</li>
 *   <li>sameSite=Lax：同站访问（同源页面 + 同源 /api）不受任何限制，跨站 GET 导航带 Cookie、跨站 POST 不带，
 *       本项目无跨站调用 /api 的场景，Lax 安全且无副作用；</li>
 *   <li>不设置 domain → host-only Cookie：仅发送到部署主机本身（当前按 IP 直访），子域/多域共享需求出现时再显式配置。</li>
 * </ul>
 * 会话有效期 7 天（原 server.servlet.session.timeout=7d 的意图，改在 Spring Session 层真正生效）。
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

    @Bean
    public DefaultCookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(false);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionTimeoutCustomizer() {
        return repository -> repository.setDefaultMaxInactiveInterval(Duration.ofDays(7));
    }
}
