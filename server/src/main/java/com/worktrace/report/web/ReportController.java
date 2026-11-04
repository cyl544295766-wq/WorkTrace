package com.worktrace.report.web;

import com.worktrace.auth.dto.LoginUser;
import com.worktrace.common.api.Result;
import com.worktrace.report.dto.GenerateReportReq;
import com.worktrace.report.entity.BizReport;
import com.worktrace.report.service.ReportService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 报告生成接口。
 *
 * <p>从 <b>HttpSession</b> 取当前登录用户（user_id），调用 Service 生成并返回 Result。
 * 登录态由 {@code LoginInterceptor} 拦截 /api/**（登录除外）保障；这里再判空兜底。
 *
 * <ul>
 *   <li>POST /api/reports/generate —— 生成报告（body: 报告类型 + 起止日期）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 从会话取当前登录用户（拦截器已保证，此处防御性判空） */
    private LoginUser currentUser(HttpSession session) {
        return (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
    }

    /** POST /api/reports/generate 生成并保存 AI 报告 */
    @PostMapping("/generate")
    public Result<BizReport> generate(@Valid @RequestBody GenerateReportReq req, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(20001, "未登录或会话已过期");
        }
        if (req.endDate().isBefore(req.startDate())) {
            return Result.fail(400, "结束日期不能早于开始日期");
        }
        try {
            return Result.ok(reportService.generateAndSaveReport(user.getId(), req));
        } catch (Exception e) {
            // 大模型调用失败 / 配置缺失 / 响应不可解析，统一包成业务错误返回
            return Result.fail(500, "报告生成失败: " + e.getMessage());
        }
    }
}
