package com.worktrace.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worktrace.report.dto.GenerateReportReq;
import com.worktrace.report.entity.BizReport;
import com.worktrace.report.mapper.BizReportMapper;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.mapper.BizTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 报告生成：查任务 → 拼 Prompt → 调大模型（OpenAI / DeepSeek Chat Completions）→ 落库。
 *
 * <p>模型调用通过 {@link RestTemplate} POST 发送，Bearer 认证传 apiKey，body 传 model。
 * 配置项（application.yml 或环境变量）：
 * <pre>
 * ai.base-url  = https://api.deepseek.com/v1   （OpenAI 用 https://api.openai.com/v1）
 * ai.api-key   = ${AI_API_KEY:}
 * ai.model     = deepseek-chat                 （OpenAI 用 gpt-4o-mini）
 * </pre>
 */
@Service
public class ReportService {

    private final BizTaskMapper bizTaskMapper;
    private final BizReportMapper bizReportMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ai.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.model:deepseek-chat}")
    private String model;

    public ReportService(BizTaskMapper bizTaskMapper,
                         BizReportMapper bizReportMapper,
                         ObjectMapper objectMapper) {
        this.bizTaskMapper = bizTaskMapper;
        this.bizReportMapper = bizReportMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate();
    }

    /** 连接 10s / 读取 60s：大模型生成可能较慢，避免无限等待 */
    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    /**
     * 生成并保存报告。
     *
     * @param userId 会话用户 id
     * @param req    报告类型 + 起止日期
     * @return 落库后的报告（含 content = Markdown）
     */
    public BizReport generateAndSaveReport(Long userId, GenerateReportReq req) {
        // ① 查该用户在 [startDate, endDate] 内（按 plan_date）的任务
        List<BizTask> tasks = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                .eq(BizTask::getUserId, userId)
                .ge(BizTask::getPlanDate, req.startDate())
                .le(BizTask::getPlanDate, req.endDate())
                .orderByAsc(BizTask::getPlanDate));

        // ② 任务列表 → 结构化 Prompt 上下文
        String prompt = buildPrompt(req.reportType(), req.startDate(), req.endDate(), tasks);

        // ③ 调用大模型
        String markdown = callChatModel(prompt);

        // ④ Markdown 报告落库
        BizReport report = new BizReport();
        report.setUserId(userId);
        report.setReportType(req.reportType());
        report.setStartDate(req.startDate());
        report.setEndDate(req.endDate());
        report.setContent(markdown);
        report.setAiModel(model);
        report.setCreateTime(LocalDateTime.now());
        bizReportMapper.insert(report);
        return report;
    }

    /** 把任务列表拼成结构化 Prompt */
    private String buildPrompt(String reportType, LocalDate startDate, LocalDate endDate, List<BizTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下任务数据生成一份 ").append(reportType).append(" 工作报告，使用 Markdown 格式。\n");
        sb.append("时间范围：").append(startDate).append(" 至 ").append(endDate).append('\n');
        sb.append("任务总数：").append(tasks.size()).append("\n\n任务清单：\n");

        int index = 1;
        for (BizTask task : tasks) {
            sb.append(index++).append(". ").append(task.getTitle())
                    .append("（状态：").append(statusLabel(task.getStatus()))
                    .append("，优先级：").append(priorityLabel(task.getPriority()));
            if (task.getPlanDate() != null) {
                sb.append("，计划：").append(task.getPlanDate());
            }
            if (task.getActualHours() != null) {
                sb.append("，工时：").append(task.getActualHours()).append("h");
            }
            sb.append('）');
            if (task.getContent() != null && !task.getContent().isBlank()) {
                sb.append(" — ").append(task.getContent());
            }
            sb.append('\n');
        }

        sb.append("\n输出要求：按「概览 / 完成情况 / 问题与延期 / 后续计划」等小节组织，直接输出 Markdown 正文，不要额外说明。");
        return sb.toString();
    }

    /**
     * 调用 OpenAI / DeepSeek 兼容的 Chat Completions 接口，返回 Markdown 正文。
     *
     * @throws IllegalStateException 未配置 apiKey 或响应不可解析
     */
    private String callChatModel(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 AI api-key（application.yml 的 ai.api-key 或环境变量 AI_API_KEY）");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", "你是一个专业的工作汇报助手，输出结构化的 Markdown 报告。"),
                Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        String url = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "chat/completions";
        String raw = restTemplate.postForObject(url, new HttpEntity<>(requestBody, headers), String.class);

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("大模型返回为空");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析大模型响应失败: " + e.getMessage(), e);
        }
    }

    private String statusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待办";
            case 1 -> "进行中";
            case 2 -> "已完成";
            case 3 -> "已延期";
            default -> "未知";
        };
    }

    private String priorityLabel(Integer priority) {
        if (priority == null) {
            return "未知";
        }
        return switch (priority) {
            case 1 -> "低";
            case 2 -> "中";
            case 3 -> "高";
            default -> "未知";
        };
    }
}
