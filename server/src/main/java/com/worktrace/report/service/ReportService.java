package com.worktrace.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.worktrace.common.exception.BizException;
import com.worktrace.report.dto.GenerateReportReq;
import com.worktrace.report.dto.ReportItemVO;
import com.worktrace.report.entity.BizReport;
import com.worktrace.report.mapper.BizReportMapper;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.entity.BizTaskLog;
import com.worktrace.task.mapper.BizTaskLogMapper;
import com.worktrace.task.mapper.BizTaskMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * AI 报告生成：查任务（+跟进记录）→ 拼 Prompt → 调大模型（OpenAI / DeepSeek Chat Completions）→ 落库。
 *
 * <p>模型调用通过 {@link RestTemplate}（同步）与 {@link HttpClient}（流式）发送，Bearer 认证传 apiKey。
 * 配置项（application.yml 或环境变量）：
 * <pre>
 * ai.base-url  = https://api.deepseek.com/v1   （OpenAI 用 https://api.openai.com/v1）
 * ai.api-key   = ${AI_API_KEY:}
 * ai.model     = deepseek-chat                 （OpenAI 用 gpt-4o-mini）
 * </pre>
 *
 * <p>可靠性与成本设计：
 * <ul>
 *   <li><b>同用户生成冷却</b>：两次生成至少间隔 30 秒（防误触连点 / 接口滥用导致重复计费）；</li>
 *   <li><b>输出上限</b>：按报告类型设 max_tokens（日报 800 / 周报 1600 / 月报 2200），成本可控；</li>
 *   <li><b>上下文预算</b>：未完成任务全量展开（独立上限），已完成任务按预算展开，跟进记录总量受限，
 *       文本逐字段截断——长周期（如整月数百任务）不会撑爆 prompt；</li>
 *   <li><b>部分内容保全</b>：流式中途失败时，已生成的部分内容附带中断标记落库，用户等待不白费。</li>
 * </ul>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final BizTaskMapper bizTaskMapper;
    private final BizTaskLogMapper bizTaskLogMapper;
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

    /** 同用户两次生成至少间隔 30 秒：防误触连点与成本滥用（单机 Caffeine，参照 IdempotentAspect） */
    private static final long GENERATE_COOLDOWN_MS = 30_000L;
    private final Cache<Long, Long> generateCooldown = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMillis(GENERATE_COOLDOWN_MS * 3))
            .build();

    public ReportService(BizTaskMapper bizTaskMapper,
                         BizTaskLogMapper bizTaskLogMapper,
                         BizReportMapper bizReportMapper,
                         ObjectMapper objectMapper) {
        this.bizTaskMapper = bizTaskMapper;
        this.bizTaskLogMapper = bizTaskLogMapper;
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

    /** 生成频率守卫：冷却期内抛业务异常（Controller 转 Result / SSE error 事件） */
    private void guardGenerateCooldown(Long userId) {
        long now = System.currentTimeMillis();
        Long last = generateCooldown.getIfPresent(userId);
        if (last != null && now - last < GENERATE_COOLDOWN_MS) {
            throw new BizException(429, "生成过于频繁，请约 30 秒后再试");
        }
        generateCooldown.put(userId, now);
    }

    /* ==================== 历史报告记录（列表 / 详情 / 删除 / 编辑保存） ==================== */

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
     * 编辑保存报告正文（仅本人，只更新 content；归属/类型/日期不动）。
     *
     * @return 更新后的报告；不存在或非本人返回 null
     */
    public BizReport updateContent(Long userId, Long id, String content) {
        if (getOwnedReport(userId, id) == null) {
            return null;
        }
        BizReport upd = new BizReport();
        upd.setId(id);
        upd.setContent(content);
        bizReportMapper.updateById(upd); // 只更新非 null 字段（MyBatis-Plus 默认策略）
        return getOwnedReport(userId, id);
    }

    /* ==================== 同步生成 ==================== */

    /**
     * 生成并保存报告。
     *
     * @param userId 会话用户 id
     * @param req    报告类型 + 起止日期
     * @return 落库后的报告（含 content = Markdown）
     * @throws BizException 生成冷却期内（429）
     */
    public BizReport generateAndSaveReport(Long userId, GenerateReportReq req) {
        guardGenerateCooldown(userId);

        // ① 查该用户在 [startDate, endDate] 内（按 plan_date）的任务
        List<BizTask> tasks = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                .eq(BizTask::getUserId, userId)
                .ge(BizTask::getPlanDate, req.startDate())
                .le(BizTask::getPlanDate, req.endDate())
                .orderByAsc(BizTask::getPlanDate));

        // ② 任务列表 → 结构化 Prompt 上下文
        String prompt = buildPrompt(req.reportType(), req.startDate(), req.endDate(), tasks);

        // ③ 调用大模型（输出上限按报告类型）
        String markdown = callChatModel(prompt, outputBudget(req.reportType()));

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

    /* ==================== Prompt 与数据上下文 ==================== */

    /** 跟进记录日期的显示格式（MM-dd） */
    private static final DateTimeFormatter LOG_DATE = DateTimeFormatter.ofPattern("MM-dd");

    /** 未完成任务逐条展开上限（极端积压时保护上下文；已完成任务另有按类型的预算） */
    private static final int UNFINISHED_CAP = 80;

    /** 单任务最多引用的跟进记录条数 */
    private static final int LOGS_PER_TASK = 3;

    /**
     * 输出 token 上限（max_tokens）：防失控长文与成本不可控；按报告类型放大。
     */
    private int outputBudget(String reportType) {
        return switch (reportType) {
            case "DAILY" -> 800;
            case "WEEKLY" -> 1_600;
            default -> 2_200;
        };
    }

    /** 已完成任务逐条展开预算（未完成任务不受此预算限制） */
    private int detailBudget(String reportType) {
        return switch (reportType) {
            case "DAILY" -> 30;
            case "WEEKLY" -> 60;
            default -> 100;
        };
    }

    /** 跟进记录总行数预算 */
    private int logBudget(String reportType) {
        return switch (reportType) {
            case "DAILY" -> 30;
            case "WEEKLY" -> 40;
            default -> 60;
        };
    }

    /** 文本截断：压平空白、限长（防单条超长文本挤占上下文） */
    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * 任务数据 → 结构化 Prompt。
     *
     * <p>上下文组织（让模型直接拿到可写进报告的结构化事实）：
     * <ul>
     *   <li><b>树状清单</b>：子任务缩进挂在父任务下；范围内任务的祖先缺失时自动补拉（≤5 层回溯），
     *       保证层级完整；</li>
     *   <li><b>未完成任务全量展开</b>（上限 {@value UNFINISHED_CAP}）——它们是「问题与延期 / 后续计划」的
     *       事实来源；已完成任务按预算展开，超出部分只计数量；</li>
     *   <li><b>跟进记录</b>：每条任务下挂报告周期内该任务的用户手写叙述
     *       （≤{@value LOGS_PER_TASK} 条/任务，总行数按报告类型预算）；</li>
     *   <li><b>逐字段截断</b>：标题 40 / 详情 60 / 跟进 40 字符。</li>
     * </ul>
     */
    private String buildPrompt(String reportType, LocalDate startDate, LocalDate endDate, List<BizTask> tasks) {
        String typeCn = switch (reportType) {
            case "DAILY" -> "日报";
            case "WEEKLY" -> "周报";
            case "MONTHLY" -> "月报";
            default -> reportType;
        };

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下任务数据生成一份").append(typeCn).append("，使用 Markdown 格式。\n");
        sb.append("时间范围：").append(startDate).append(" 至 ").append(endDate).append('\n');
        sb.append("任务统计：共 ").append(tasks.size()).append(" 条（")
                .append("已完成 ").append(countStatus(tasks, 2))
                .append("，进行中 ").append(countStatus(tasks, 1))
                .append("，待处理 ").append(countStatus(tasks, 0))
                .append("，已延期 ").append(countStatus(tasks, 3)).append("）");
        long keyCount = tasks.stream().filter(t -> t.getIsKey() != null && t.getIsKey() == 1).count();
        sb.append("；重点标记 ").append(keyCount).append(" 条\n");
        sb.append("\n任务清单（子任务缩进展示；「跟进」为用户对该任务的实际工作叙述，是报告的重要事实依据）：\n");

        // ① 节点表：范围内任务 + 缺失祖先（补拉，保证树结构完整）
        Map<Long, BizTask> nodeMap = new LinkedHashMap<>();
        List<BizTask> nodes = new ArrayList<>();
        for (BizTask t : tasks) {
            if (t.getId() != null) {
                nodeMap.put(t.getId(), t);
                nodes.add(t);
            }
        }
        for (int round = 0; round < 5; round++) {
            Set<Long> missing = new LinkedHashSet<>();
            for (BizTask t : nodes) {
                Long pid = t.getParentId();
                if (pid != null && !nodeMap.containsKey(pid)) {
                    missing.add(pid);
                }
            }
            if (missing.isEmpty()) {
                break;
            }
            List<BizTask> ancestors = bizTaskMapper.selectBatchIds(missing);
            if (ancestors == null || ancestors.isEmpty()) {
                break;
            }
            for (BizTask p : ancestors) {
                if (!nodeMap.containsKey(p.getId())) {
                    nodeMap.put(p.getId(), p);
                    nodes.add(p);
                }
            }
        }

        // ② 建树：父任务在节点表内 → 挂子节点；否则视为根（父任务不在范围 / 已删除）
        Map<Long, List<BizTask>> childrenMap = new LinkedHashMap<>();
        List<BizTask> roots = new ArrayList<>();
        for (BizTask t : nodes) {
            Long pid = t.getParentId();
            if (pid != null && nodeMap.containsKey(pid)) {
                childrenMap.computeIfAbsent(pid, k -> new ArrayList<>()).add(t);
            } else {
                roots.add(t);
            }
        }
        Comparator<BizTask> byPlanDate = Comparator.comparing(BizTask::getPlanDate,
                Comparator.nullsLast(Comparator.naturalOrder()));
        roots.sort(byPlanDate);
        childrenMap.values().forEach(list -> list.sort(byPlanDate));

        // ③ 跟进记录：范围内任务在报告周期内的记录（一次查询，按任务分组）
        List<Long> taskIds = nodes.stream().map(BizTask::getId).toList();
        Map<Long, List<BizTaskLog>> logsByTask = fetchLogsGroupedByTask(taskIds, startDate, endDate);

        // ④ 树状输出：未完成任务优先保展开；已完成任务受预算控制；跟进记录受总预算控制
        int[] seq = {0};                 // 顶层序号
        int[] completedUsed = {0};
        int[] logsUsed = {0};
        int[] unfinishedUsed = {0};
        int[] completedDropped = {0};
        int[] unfinishedDropped = {0};
        StringBuilder body = new StringBuilder();
        int completedBudget = detailBudget(reportType);
        int logTotalBudget = logBudget(reportType);
        for (BizTask root : roots) {
            appendTaskTree(body, root, 0, root.getParentId() != null, nodeMap, childrenMap, logsByTask,
                    completedBudget, logTotalBudget, seq,
                    completedUsed, logsUsed, unfinishedUsed, completedDropped, unfinishedDropped);
        }
        sb.append(body);
        if (completedDropped[0] > 0) {
            sb.append("（另有 ").append(completedDropped[0])
                    .append(" 条已完成任务未逐条展开，仅计入上方统计）\n");
        }
        if (unfinishedDropped[0] > 0) {
            sb.append("（另有 ").append(unfinishedDropped[0])
                    .append(" 条未完成任务超出上限未展开，仅计入上方统计）\n");
        }

        // ⑤ 输出要求：事实边界（防虚构）+ 按类型的结构约束
        sb.append("\n输出要求：报告中的事实、进度与数字必须全部来自上方任务数据与跟进记录，不得虚构或编造。");
        if ("DAILY".equals(reportType)) {
            // 日报走精简通道：用户明确要求"不要生成的特别多"
            sb.append("日报务必精简：全文不超过 300 字；禁止表格、禁止数据罗列、禁止套话。")
                    .append("结构：开头一句话总结当日进展，然后「今日完成」（每条一行，只写任务标题，")
                    .append("跟进记录里有实际进展的可补一句）、「进行中 / 待办」（只列最重要的 1-3 条）、")
                    .append("「明日重点」（1-3 条）。无内容的小节直接省略。");
        } else {
            sb.append("按「概览 / 完成情况 / 问题与延期 / 后续计划」等小节组织，结合跟进记录写清实际进展。");
        }
        sb.append("直接输出 Markdown 正文，不要额外说明。");
        return sb.toString();
    }

    /** 统计某状态的任务条数（null 视为待办 0） */
    private long countStatus(List<BizTask> tasks, int status) {
        return tasks.stream().filter(t -> (t.getStatus() == null ? 0 : t.getStatus()) == status).count();
    }

    /** 报告周期内、属于范围内任务的跟进记录（一次查询 LIMIT 200 兜底，按 taskId 分组） */
    private Map<Long, List<BizTaskLog>> fetchLogsGroupedByTask(List<Long> taskIds, LocalDate start, LocalDate end) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<BizTaskLog> logs = bizTaskLogMapper.selectList(Wrappers.<BizTaskLog>lambdaQuery()
                .in(BizTaskLog::getTaskId, taskIds)
                .ge(BizTaskLog::getCreateTime, start.atStartOfDay())
                .lt(BizTaskLog::getCreateTime, end.plusDays(1).atStartOfDay())
                .orderByDesc(BizTaskLog::getCreateTime)
                .last("LIMIT 200"));
        Map<Long, List<BizTaskLog>> grouped = new LinkedHashMap<>();
        for (BizTaskLog l : logs) {
            if (l.getTaskId() != null && l.getContent() != null && !l.getContent().isBlank()) {
                grouped.computeIfAbsent(l.getTaskId(), k -> new ArrayList<>()).add(l);
            }
        }
        return grouped;
    }

    /**
     * 递归输出一棵任务子树（缩进 = 层级）。
     * <p>预算规则：
     * <ul>
     *   <li>已完成且整棵子树无未完成任务 → 受「已完成预算」控制，超出只计数不输出；</li>
     *   <li>已完成但子树里有未完成任务 → 仍输出（作为未完成子任务的层级容器）；</li>
     *   <li>未完成任务 → 受 {@value UNFINISHED_CAP} 上限保护。</li>
     * </ul>
     * 计数走 int[]（递归内共享），depth 硬上限防御异常数据成环。
     */
    private void appendTaskTree(StringBuilder out, BizTask node, int depth, boolean orphanRoot,
                                Map<Long, BizTask> nodeMap, Map<Long, List<BizTask>> childrenMap,
                                Map<Long, List<BizTaskLog>> logsByTask,
                                int completedBudget, int logTotalBudget, int[] seq,
                                int[] completedUsed, int[] logsUsed,
                                int[] unfinishedUsed, int[] completedDropped, int[] unfinishedDropped) {
        if (depth > 6) {
            return; // 防御：parentId 异常成环时终止递归
        }
        int status = node.getStatus() == null ? 0 : node.getStatus();
        boolean finished = status == 2;

        if (finished) {
            if (!hasUnfinishedInSubtree(node, childrenMap, 0)) {
                // 纯完成子树：受预算控制
                if (completedUsed[0] >= completedBudget) {
                    completedDropped[0]++;
                    return;
                }
                completedUsed[0]++;
            }
        } else {
            if (unfinishedUsed[0] >= UNFINISHED_CAP) {
                unfinishedDropped[0]++;
                return;
            }
            unfinishedUsed[0]++;
        }

        // 任务行：顶层带序号，子任务按层级缩进
        String bullet = depth == 0 ? (seq[0] + 1) + ". " : (depth == 1 ? "  - " : "    · ");
        if (depth == 0) {
            seq[0]++;
        }
        out.append(bullet).append(taskLine(node, depth, orphanRoot)).append('\n');

        String desc = clip(node.getContent(), 60);
        if (!desc.isEmpty()) {
            out.append("  ".repeat(depth)).append("  详情：").append(desc).append('\n');
        }

        // 跟进记录：紧跟任务行，受单任务条数与总预算限制
        List<BizTaskLog> logs = logsByTask.get(node.getId());
        if (logs != null && logsUsed[0] < logTotalBudget) {
            int emitted = 0;
            for (BizTaskLog l : logs) {
                if (emitted >= LOGS_PER_TASK || logsUsed[0] >= logTotalBudget) {
                    break;
                }
                out.append("  ".repeat(depth)).append("  · 跟进(").append(logTypeLabel(l.getLogType()))
                        .append(")：").append(clip(l.getContent(), 40));
                if (l.getCreateTime() != null) {
                    out.append("（").append(l.getCreateTime().format(LOG_DATE)).append("）");
                }
                out.append('\n');
                emitted++;
                logsUsed[0]++;
            }
        }

        for (BizTask child : childrenMap.getOrDefault(node.getId(), List.of())) {
            appendTaskTree(out, child, depth + 1, false, nodeMap, childrenMap, logsByTask,
                    completedBudget, logTotalBudget, seq,
                    completedUsed, logsUsed, unfinishedUsed, completedDropped, unfinishedDropped);
        }
    }

    /** 子树内（含自身）是否存在未完成任务（判断已完成父任务是否需作为容器保留） */
    private boolean hasUnfinishedInSubtree(BizTask node, Map<Long, List<BizTask>> childrenMap, int depth) {
        if (depth > 10) {
            return false; // 防御：异常数据成环
        }
        int status = node.getStatus() == null ? 0 : node.getStatus();
        if (status != 2) {
            return true;
        }
        for (BizTask child : childrenMap.getOrDefault(node.getId(), List.of())) {
            if (hasUnfinishedInSubtree(child, childrenMap, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 单任务行：标题 + 状态 + 元信息（缩进由调用方 bullet 控制） */
    private String taskLine(BizTask t, int depth, boolean orphanRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(clip(t.getTitle(), 40)).append("》[").append(statusTag(t)).append(']');
        List<String> meta = new ArrayList<>();
        if (t.getPriority() != null) {
            meta.add("优先级:" + priorityLabel(t.getPriority()));
        }
        if (t.getPlanDate() != null) {
            meta.add("计划:" + t.getPlanDate());
        }
        if (t.getActualHours() != null) {
            meta.add("工时:" + t.getActualHours() + "h");
        }
        if (t.getIsKey() != null && t.getIsKey() == 1) {
            meta.add("重点");
        }
        if (orphanRoot) {
            meta.add("父任务不在本范围");
        }
        if (!meta.isEmpty()) {
            sb.append("（").append(String.join("，", meta)).append("）");
        }
        return sb.toString();
    }

    /** 状态 → 短标签（进行中带进度百分比） */
    private String statusTag(BizTask t) {
        int status = t.getStatus() == null ? 0 : t.getStatus();
        return switch (status) {
            case 2 -> "已完成";
            case 1 -> "进行中" + (t.getProgress() == null ? "" : " " + t.getProgress() + "%");
            case 3 -> "已延期";
            default -> "待办";
        };
    }

    /** 跟进类型 → 中文（VERIFY=核实 NOTE=备注 DEV=开发 OTHER=其他） */
    private String logTypeLabel(String type) {
        if (type == null) {
            return "其他";
        }
        return switch (type) {
            case "VERIFY" -> "核实";
            case "NOTE" -> "备注";
            case "DEV" -> "开发";
            case "OTHER" -> "其他";
            default -> type;
        };
    }

    /* ==================== 模型调用（同步 / 流式） ==================== */

    /**
     * 调用 OpenAI / DeepSeek 兼容的 Chat Completions 接口，返回 Markdown 正文。
     *
     * @param maxTokens 输出上限（max_tokens，防失控长文）
     * @throws IllegalStateException 未配置 apiKey 或响应不可解析
     */
    private String callChatModel(String prompt, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 AI api-key（application.yml 的 ai.api-key 或环境变量 AI_API_KEY）");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", "你是一个专业的工作汇报助手，输出结构化的 Markdown 报告。"),
                Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", maxTokens);

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
     *   <li>error 事件：{@code {"message":"..."}}（流中断已保全部分内容时额外含
     *       {@code "partial":true,"reportId":...}）</li>
     *   <li>done 事件：{@code {"reportId":...}} 生成完毕并已落库</li>
     * </ul>
     *
     * @throws BizException 生成冷却期内（429，Controller 转 SSE error 事件）
     */
    public void streamGenerateReport(Long userId, GenerateReportReq req, SseEmitter emitter) {
        guardGenerateCooldown(userId);

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
                }, outputBudget(req.reportType()));

                report.setContent(markdown.toString());
                bizReportMapper.insert(report);

                emitter.send(SseEmitter.event()
                        .name("done").data(Map.of("reportId", report.getId())));
                emitter.complete(); // ★ 传输完毕，正常结束
            } catch (Exception e) {
                // ★ 流中断时，已生成的部分内容也落库（附中断标记），用户等待不白费
                Long partialId = savePartialOnFailure(report, markdown);
                String message = partialId != null
                        ? "生成中断，已保存部分内容（可在历史记录查看）"
                        : "报告生成失败: " + e.getMessage();
                log.warn("报告生成中断/失败: {}（partialId={}）", e.getMessage(), partialId);
                sendErrorEvent(emitter, message, partialId);
            }
        });
    }

    /** 失败兜底：已流出部分内容 → 附中断标记落库，返回报告 id（无可存内容或落库失败返回 null） */
    private Long savePartialOnFailure(BizReport report, StringBuilder markdown) {
        if (report.getId() != null || markdown.isEmpty()) {
            return null;
        }
        try {
            report.setContent(markdown + "\n\n> ⚠️ 生成中断，以上为已生成的部分内容。");
            bizReportMapper.insert(report);
            return report.getId();
        } catch (Exception e) {
            log.warn("报告部分内容落库失败: {}", e.getMessage());
            return null;
        }
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
    private void streamChatModel(String prompt, ChunkConsumer onChunk, int maxTokens) {
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
        requestBody.put("max_tokens", maxTokens);

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
        sendErrorEvent(emitter, message, null);
    }

    /** error 事件变体：流中断已保全部分内容时，附带 partial + reportId（前端据此提示与刷新历史） */
    public static void sendErrorEvent(SseEmitter emitter, String message, Long partialReportId) {
        Map<String, Object> data = partialReportId == null
                ? Map.of("message", message)
                : Map.of("message", message, "partial", true, "reportId", partialReportId);
        try {
            emitter.send(SseEmitter.event().name("error").data(data));
            emitter.complete();
        } catch (Exception ignore) {
            // 客户端已断开等场景，无需再处理
        }
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
