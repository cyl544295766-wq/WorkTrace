package com.worktrace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.time.ZoneId;

/**
 * WorkTrace 单体应用入口。
 */
@SpringBootApplication
public class WorkTraceApplication {

    private static final Logger log = LoggerFactory.getLogger(WorkTraceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(WorkTraceApplication.class, args);
    }

    /**
     * 启动日志：banner 由 Spring Boot 输出，这里补充 profile / 端口 / 时区。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logStartupSummary(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String[] profiles = env.getActiveProfiles().length == 0
                ? env.getDefaultProfiles()
                : env.getActiveProfiles();

        int port = -1;
        if (event.getApplicationContext() instanceof ServletWebServerApplicationContext ctx
                && ctx.getWebServer() != null) {
            port = ctx.getWebServer().getPort();
        }

        log.info("WorkTrace started | profile={} | port={} | timezone={}",
                String.join(",", profiles), port, ZoneId.systemDefault());
    }
}
