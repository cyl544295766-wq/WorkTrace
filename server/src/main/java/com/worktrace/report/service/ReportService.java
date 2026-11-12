package com.worktrace.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worktrace.report.dto.GenerateReportReq;
import com.worktrace.report.dto.ReportItemVO;
import com.worktrace.report.entity.BizReport;
import com.worktrace.report.mapper.BizReportMapper;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.mapper.BizTaskMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

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

    /** 流式调用专用 HTTP 客户端（JDK 内置，天然支持逐行读取响应体） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** SSE 推送线程池：大模型流的读取/推送耗时，绝不能占用 Tomcat 工作线程 */
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "report-sse");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdownSseExecutor() {
        sseExecutor.shutdownNow();
    }

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

    /* ==================== 历史报告记录（列表 / 详情 / 删除） ==================== */

    /** 分页查当前用户的报告记录（按创建时间倒序）；列表只回摘要，不传全文 */
    public Page<ReportItemVO> pageReports(Long userId, long page, long size) {
        Page<BizReport> pg = bizReportMapper.selectPage(new Page<>(page, size),
                Wrappers.<BizReport>lambdaQuery()
                        .eq(BizReport::getUserId, userId)
                        .orderByDesc(BizReport::getCreateTime));
        // 手动映射成 VO（Page.convert 本版本返回 IPage，与 TaskController 的 Page 契约不一致）
        Page<ReportItemVO> out = new Page<>(pg.getCurrent(), pg.getSize(), pg.getTotal());
        out.setRecords(pg.getRecords().stream().map(ReportService::toItem).toList());
        return out;
    }

    /** 报告行 → 列表 VO（正文压成 80 字单行摘要） */
    private static ReportItemVO toItem(BizReport r) {
        String content = r.getContent() == null ? "" : r.getContent().strip();
        String summary = content.length() > 80 ? content.substring(0, 80) + "…" : content;
        summary = summary.replace('\n', ' ');
        return new ReportItemVO(r.getId(), r.getReportType(), r.getStartDate(), r.getEndDate(),
                r.getAiModel(), r.getCreateTime(), summary);
    }

    /** 按归属查单条报告（含全文）；不存在或非本人返回 null */
    public BizReport getOwnedReport(Long userId, Long id) {
        return bizReportMapper.selectOne(Wrappers.<BizReport>lambdaQuery()
                .eq(BizReport::getUserId, userId)
                .eq(BizReport::getId, id));
    }

    /** 删除报告记录（仅本人，物理删除）；true=已删除 */
    public boolean deleteReport(Long userId, Long id) {
        return bizReportMapper.delete(Wrappers.<BizReport>lambdaQuery()
                .eq(BizReport::getUserId, userId)
                .eq(BizReport::getId, id)) > 0;
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
        String typeCn = switch (reportType) {
            case "DAILY" -> "日报";
            case "WEEKLY" -> "周报";
            case "MONTHLY" -> "月报";
            default -> reportType;
        };
        sb.append("请根据以下任务数据生成一份").append(typeCn).append("，使用 Markdown 格式。\n");
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

        if ("DAILY".equals(reportType)) {
            // 日报走精简通道：用户明确要求"不要生成的特别多"
            sb.append("\n输出要求（日报，务必精简）：全文不超过 300 字；禁止表格、禁止数据罗列、禁止套话。"
                    + "结构：开头一句话总结当日进展，然后「今日完成」（每条一行，只写任务标题）、「进行中 / 待办」（只列最重要的 1-3 条）、"
                    + "「明日重点」（1-3 条）。无内容的小节直接省略。直接输出 Markdown 正文，不要额外说明。");
        } else {
            sb.append("\n输出要求：按「概览 / 完成情况 / 问题与延期 / 后续计划」等小节组织，直接输出 Markdown 正文，不要额外说明。");
        }
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

    /* ==================== SSE 流式生成 ==================== */

    /** 单块文本回调：streamChatModel 每解析出一个增量 Chunk 就回调一次 */
    @FunctionalInterface
    interface ChunkConsumer {
        void accept(String chunk);
    }

    /**
     * 流式生成报告（SSE）。
     *
     * <p>任务查询与 Prompt 构建在调用线程完成；大模型流的读取与推送提交到独立线程池。
     * 推给前端的事件约定：
     * <ul>
     *   <li>默认 message 事件：增量 Markdown 文本块（可拼接连成全文）</li>
     *   <li>error 事件：{@code {"message":"..."}} 生成失败</li>
     *   <li>done 事件：{@code {"reportId":...}} 生成完毕并已落库</li>
     * </ul>
     */
    public void streamGenerateReport(Long userId, GenerateReportReq req, SseEmitter emitter) {
        // ① 查该用户在 [startDate, endDate] 内（按 plan_date）的任务
        List<BizTask> tasks = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                .eq(BizTask::getUserId, userId)
                .ge(BizTask::getPlanDate, req.startDate())
                .le(BizTask::getPlanDate, req.endDate())
                .orderByAsc(BizTask::getPlanDate));

        // ② 任务列表 → 结构化 Prompt 上下文
        String prompt = buildPrompt(req.reportType(), req.startDate(), req.endDate(), tasks);

        // ③ 落库对象先备好，流结束后补 content 再 insert
        BizReport report = new BizReport();
        report.setUserId(userId);
        report.setReportType(req.reportType());
        report.setStartDate(req.startDate());
        report.setEndDate(req.endDate());
        report.setAiModel(model);
        report.setCreateTime(LocalDateTime.now());

        // ④ 大模型流式调用与 SSE 推送在独立线程执行
        sseExecutor.execute(() -> {
            StringBuilder markdown = new StringBuilder();
            try {
                streamChatModel(prompt, chunk -> {
                    markdown.append(chunk);
                    try {
                        // ★ 每收到一块数据，实时推送给前端
                        emitter.send(SseEmitter.event().data(chunk));
                    } catch (IOException e) {
                        // 推送失败（客户端断开等）→ 抛运行时异常，由下方统一走 error 事件
                        throw new IllegalStateException("SSE 推送失败，客户端可能已断开", e);
                    }
                });

                report.setContent(markdown.toString());
                bizReportMapper.insert(report);

                emitter.send(SseEmitter.event()
                        .name("done").data(Map.of("reportId", report.getId())));
                emitter.complete(); // ★ 传输完毕，正常结束
            } catch (Exception e) {
                // ★ 任何环节出错（连接模型失败 / 解析失败 / 客户端断开）都通过 error 事件通知前端
                sendErrorEvent(emitter, "报告生成失败: " + e.getMessage());
            }
        });
    }

    /**
     * 调用 OpenAI / DeepSeek 兼容 Chat Completions 接口（stream=true），
     * 逐行解析模型返回的 SSE 文本流，把每个增量 Chunk 交给 onChunk 回调。
     *
     * <p>响应行格式：
     * <pre>
     * data: {"choices":[{"delta":{"content":"文本块"}}]}
     * data: [DONE]
     * </pre>
     */
    private void streamChatModel(String prompt, ChunkConsumer onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 AI api-key（application.yml 的 ai.api-key 或环境变量 AI_API_KEY）");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", "你是一个专业的工作汇报助手，输出结构化的 Markdown 报告。"),
                Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true); // ★ 开启流式模式

        String url = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "chat/completions";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }

        HttpResponse<Stream<String>> response;
        try {
            // ofLines：响应体按行拆成 Stream，天然按 Chunk 到达顺序阻塞读取
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new IllegalStateException("连接大模型失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断", e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("大模型返回 HTTP " + response.statusCode()
                    + "（检查 base-url / api-key / model 配置）");
        }

        try (Stream<String> lines = response.body()) {
            boolean finished = false;
            for (Iterator<String> it = lines.iterator(); it.hasNext() && !finished; ) {
                String line = it.next();
                if (line == null || !line.startsWith("data:")) {
                    continue; // SSE 空行 / 注释行
                }
                String payload = line.substring("data:".length()).trim();
                if ("[DONE]".equals(payload)) {
                    finished = true; // 流结束标记
                    break;
                }
                JsonNode delta = objectMapper.readTree(payload)
                        .path("choices").path(0).path("delta");
                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    onChunk.accept(content); // ★ 逐块回调（失败会抛出，由上层统一捕获）
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取大模型流式响应失败: " + e.getMessage(), e);
        }
    }

    /** 通过 error 事件通知前端生成失败，然后正常关闭连接（连接已断时静默忽略） */
    public static void sendErrorEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
            emitter.complete();
        } catch (Exception ignore) {
            // 客户端已断开等场景，无需再处理
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
