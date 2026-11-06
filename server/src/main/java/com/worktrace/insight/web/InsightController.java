package com.worktrace.insight.web;

import com.worktrace.auth.dto.LoginUser;
import com.worktrace.common.api.Result;
import com.worktrace.insight.dto.InsightVO;
import com.worktrace.insight.service.InsightService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 工作洞察（REST）。
 *
 * <p>与任务接口同一套会话约定：从 HttpSession 取当前登录用户（{@code LOGIN_USER}），
 * 登录态由 {@code LoginInterceptor} 拦 /api/** 保障，此处判空兜底。
 *
 * <ul>
 *   <li>GET /api/insights/work —— 首页「AI 工作洞察」文案（AI 生成，失败降级规则文案）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/insights")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    /** 从会话取当前登录用户（拦截器已保证，此处防御性判空） */
    private LoginUser currentUser(HttpSession session) {
        return (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
    }

    /** GET /api/insights/work 工作洞察 */
    @GetMapping("/work")
    public Result<InsightVO> work(HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        return Result.ok(insightService.getWorkInsight(user.getId()));
    }
}
