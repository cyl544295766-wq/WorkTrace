package com.worktrace.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final ThreadPoolExecutor sseExecutor;

    /**
     * 进行中的 SSE 生成（任务句柄 + 上游模型流句柄）：客户端断开 / SSE 超时 / 应用关闭时
     * 据此取消任务并关闭上游流，避免后台任务持续占用线程与连接。
     */
    private final Set<InflightGeneration> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 有界线程池（替代原 newFixedThreadPool 的无界 LinkedBlockingQueue）：
     * <ul>
     *   <li>线程数默认 4——AI 生成是长 IO 任务（单次 10-60s），4 并发已覆盖个人/小团队场景，
     *       再多只会放大上游 token 成本与超时压力；</li>
     *   <li>队列容量默认 16——吸收短时突发；队列满走 {@code AbortPolicy} 快速拒绝，
     *       由调用方转成 SSE error 事件（"服务繁忙，请稍后重试"），绝不无界堆积任务。</li>
     * </ul>
     * 两者可用 {@code report.sse.threads} / {@code report.sse.queue-capacity} 覆盖。
     */
    public ReportService(BizTaskMapper bizTaskMapper,
                         BizTaskLogMapper bizTaskLogMapper,
                         BizReportMapper bizReportMapper,
                         ObjectMapper objectMapper,
                         @Value("${report.sse.threads:4}") int sseThreads,
                         @Value("${report.sse.queue-capacity:16}") int sseQueueCapacity) {
        this.bizTaskMapper = bizTaskMapper;
        this.bizTaskLogMapper = bizTaskLogMapper;
        this.bizReportMapper = bizReportMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate();
        AtomicInteger seq = new AtomicInteger();
        this.sseExecutor = new ThreadPoolExecutor(
                Math.max(1, sseThreads), Math.max(1, sseThreads), 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, sseQueueCapacity)),
                r -> {
                    Thread t = new Thread(r, "report-sse-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 单个进行中的 SSE 生成：取消状态 + 任务句柄 + 上游模型流句柄。
     *
     * <p>线程安全的取消/登记协议（顺序任意均不漏）：{@link #cancel()} 先置取消标志再处理已登记句柄；
     * {@link #registerFuture} / {@link #registerUpstream} 登记后再查一次标志——若登记前已取消，
     * 立即取消该任务 / 关闭该流。两侧双端检查，无论取消与登记以何种顺序交错，句柄最终都会被处理。
     */
    private static final class InflightGeneration {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Future<?>> future = new AtomicReference<>();
        private final AtomicReference<Stream<String>> upstream = new AtomicReference<>();
        private final ThreadPoolExecutor pool;

        InflightGeneration(ThreadPoolExecutor pool) {
            this.pool = pool;
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        /** 标记取消并处理已登记句柄：中断任务线程、关闭上游流（阻塞读取立即抛异常退出） */
        void cancel() {
            cancelled.set(true);
            cancelFuture();
            Stream<String> s = upstream.get();
            if (s != null) {
                s.close(); // 关闭上游流 → 另一线程里阻塞中的读取立即失败
            }
        }

        /** 登记任务句柄；登记前已被取消则立即补偿取消（含排队中任务的立即出队） */
        void registerFuture(Future<?> f) {
            future.set(f);
            if (cancelled.get()) {
                cancelFuture();
            }
        }

        /** 登记上游模型流；登记前已被取消则立即补偿关闭 */
        void registerUpstream(Stream<String> s) {
            upstream.set(s);
            if (cancelled.get()) {
                s.close();
            }
        }

        /** 上游流读取结束后清句柄（仅当仍是当前登记值，避免误清后登记的新句柄） */
        void clearUpstream(Stream<String> s) {
            upstream.compareAndSet(s, null);
        }

        /**
         * 取消任务句柄：运行中 → 中断；仍在排队 → FutureTask 置取消态<b>并从队列移除</b>，
         * 立即释放排队容量（否则被取消的任务会一直占着队列槽位直到有空闲线程拾取）。
         */
        private void cancelFuture() {
            Future<?> f = future.get();
            if (f == null) {
                return;
            }
            f.cancel(true);
            if (f instanceof Runnable r) {
                pool.remove(r); // 不在队列中时为 no-op；在队列中则立即出队
            }
        }
    }

    @PreDestroy
    public void shutdownSseExecutor() {
        int cancelled = 0;
        for (InflightGeneration inflight : inFlight) {
            inflight.cancel(); // 中断运行中的任务并关闭其上游流；排队中的任务随 shutdownNow 一并丢弃
            cancelled++;
        }
        sseExecutor.shutdownNow();
        if (cancelled > 0) {
            log.info("应用关闭：已取消 {} 个进行中的报告生成任务并关闭上游模型流", cancelled);
        }
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

        // ① 数据准备 + Prompt 构建（与 SSE 路径共用 buildPrompt：
        //    完整统计走数据库聚合、明细/跟进按 Prompt 预算限量读取，不再整段载入周期内任务）
        String prompt = buildPrompt(userId, req.reportType(), req.startDate(), req.endDate());

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
     * 任务数据 → 结构化 Prompt（同步与 SSE 共用的数据准备入口）。
     *
     * <p>上下文组织（与历史实现逐字节一致），数据读取按预算收紧：
     * <ul>
     *   <li><b>完整统计走数据库聚合</b>（总数/状态分布/重点数一条聚合查询）——统计不依赖明细截断，
     *       任何限量读取都不会让统计变少；</li>
     *   <li><b>树状清单</b>：子任务缩进挂在父任务下；祖先缺失自动补拉（≤5 层回溯）；</li>
     *   <li><b>未完成任务全量展开</b>（上限 {@value UNFINISHED_CAP}）；已完成任务按预算展开，
     *       超出部分只计数量（「另有 N 条…」说明，计数来自决策遍历，语义不变）；</li>
     *   <li><b>明细按 Prompt 预算限量</b>：决策遍历游标分批加载子级——只访问「根 + 展开节点的子级」，
     *       根/子节点均 (planDate, id) 稳定排序分页（≤{@value NODE_PAGE_SIZE} 行/页），范围过滤下推 SQL；
     *       被剪枝子树的内部一行不读；标题/正文/优先级等明细列只对展开集读取；</li>
     *   <li><b>跟进记录只查展开集</b>（周期范围、单任务 {@value LOGS_PER_TASK} 条、总预算规则不变）；</li>
     *   <li><b>逐字段截断</b>：标题 40 / 详情 60 / 跟进 40 字符。</li>
     * </ul>
     *
     * <p>数据归属：所有查询均限定 userId —— parentId 一旦指向他人任务 id，查不到，
     * 落为孤儿根（"父任务不在本范围"），别人的任务/跟进不进报告。
     */
    private String buildPrompt(Long userId, String reportType, LocalDate startDate, LocalDate endDate) {
        String typeCn = switch (reportType) {
            case "DAILY" -> "日报";
            case "WEEKLY" -> "周报";
            case "MONTHLY" -> "月报";
            default -> reportType;
        };

        // ① 完整统计 = 数据库聚合（不读任务行）
        Map<String, Object> statsRow = fetchStatsAggregate(userId, startDate, endDate);
        int total = num(statsRow, "total");
        int done = num(statsRow, "done_cnt");
        int doing = num(statsRow, "doing_cnt");
        int delayed = num(statsRow, "delayed_cnt");
        int todo = total - done - doing - delayed; // status 非空列：口径同原内存统计（null 视为待办）
        int keyCount = num(statsRow, "key_cnt");

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下任务数据生成一份").append(typeCn).append("，使用 Markdown 格式。\n");
        sb.append("时间范围：").append(startDate).append(" 至 ").append(endDate).append('\n');
        sb.append("任务统计：共 ").append(total).append(" 条（")
                .append("已完成 ").append(done)
                .append("，进行中 ").append(doing)
                .append("，待处理 ").append(todo)
                .append("，已延期 ").append(delayed).append("）");
        sb.append("；重点标记 ").append(keyCount).append(" 条\n");
        sb.append("\n任务清单（子任务缩进展示；「跟进」为用户对该任务的实际工作叙述，是报告的重要事实依据）：\n");

        // ② 祖先骨架（必要祖先，≤5 轮补拉）+ 容器判定集（完成节点含未完成后代 → 恒展开）
        Map<Long, BizTask> skeleton = loadAncestorSkeleton(userId, startDate, endDate);
        Set<Long> skeletonIds = skeleton.keySet();
        Set<Long> containerIds = loadContainerIds(userId, startDate, endDate, skeleton);

        // ③ 决策遍历（根节点游标分批）：预算规则不变，只访问根与展开节点的子级；
        //    根/子节点均 (planDate, id) 稳定排序游标分批（≤NODE_PAGE_SIZE/页），一次不返回全部节点
        Comparator<BizTask> byPlanDate = Comparator.comparing(BizTask::getPlanDate,
                Comparator.nullsLast(Comparator.naturalOrder()));

        int completedBudget = detailBudget(reportType);
        int[] seq = {0};                 // 顶层序号
        int[] completedUsed = {0};
        int[] unfinishedUsed = {0};
        int[] completedDropped = {0};
        int[] unfinishedDropped = {0};
        Set<Long> expandedIds = new LinkedHashSet<>();
        Map<Long, Integer> seqById = new LinkedHashMap<>();
        // 空值段判定：plan_date 为空的行只可能来自祖先骨架（周期内 BETWEEN 排除空值）——
        // 骨架内存信息证明无空值行的分页跳过空值段探测
        boolean skeletonHasNullDate = false;
        Set<Long> nullDateParents = new LinkedHashSet<>();
        for (BizTask t : skeleton.values()) {
            if (t.getPlanDate() == null) {
                skeletonHasNullDate = true;
                if (t.getParentId() != null) {
                    nullDateParents.add(t.getParentId());
                }
            }
        }
        NodePager rootPager = new NodePager(userId, null, startDate, endDate, skeletonIds, !skeletonHasNullDate);
        while (true) {
            List<BizTask> page = rootPager.nextPage();
            if (page.isEmpty()) {
                break;
            }
            for (BizTask root : page) {
                planTaskTree(userId, root, 0, startDate, endDate, skeletonIds, nullDateParents, containerIds,
                        completedBudget, seq, completedUsed, unfinishedUsed,
                        completedDropped, unfinishedDropped, expandedIds, seqById);
            }
        }

        // ④ 明细按预算：只读展开集的完整行（标题/正文/优先级/进度/工时/重点）
        Map<Long, BizTask> detailById = fetchDetailRows(userId, expandedIds);
        Map<Long, List<BizTask>> renderChildren = new LinkedHashMap<>();
        List<BizTask> renderRoots = new ArrayList<>();
        for (BizTask t : detailById.values()) {
            Long pid = t.getParentId();
            if (pid != null && detailById.containsKey(pid)) {
                renderChildren.computeIfAbsent(pid, k -> new ArrayList<>()).add(t);
            } else {
                renderRoots.add(t);
            }
        }
        renderRoots.sort(byPlanDate);
        renderChildren.values().forEach(list -> list.sort(byPlanDate));

        // ⑤ 跟进记录：只查展开集的任务（周期范围/单任务条数/总预算规则不变）
        Map<Long, List<BizTaskLog>> logsByTask =
                fetchLogsGroupedByTask(userId, new ArrayList<>(expandedIds), startDate, endDate);

        // ⑥ 树状渲染 + 超额说明（计数来自决策遍历，语义与历史实现一致）
        Map<Long, String> contentById = new LinkedHashMap<>();
        detailById.forEach((id, t) -> contentById.put(id, t.getContent() == null ? "" : t.getContent()));
        int[] logsUsed = {0};
        int logTotalBudget = logBudget(reportType);
        StringBuilder body = new StringBuilder();
        for (BizTask root : renderRoots) {
            renderTaskTree(body, root, 0, root.getParentId() != null, renderChildren, logsByTask,
                    contentById, expandedIds, seqById, logTotalBudget, logsUsed);
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

        // ⑦ 输出要求：事实边界（防虚构）+ 按类型的结构约束
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

    /**
     * 完整统计聚合（唯一一条查询）：周期内总数/状态分布/重点数。
     * 统计口径与历史内存统计一致（todo = 其余状态差值；status 非空列，null 视为待办）。
     */
    private Map<String, Object> fetchStatsAggregate(Long userId, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> rows = bizTaskMapper.selectMaps(Wrappers.<BizTask>query()
                .select("COUNT(*) AS total",
                        "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS done_cnt",
                        "SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS doing_cnt",
                        "SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END) AS delayed_cnt",
                        "SUM(CASE WHEN is_key = 1 THEN 1 ELSE 0 END) AS key_cnt")
                .eq("user_id", userId)
                .ge("plan_date", startDate)
                .le("plan_date", endDate));
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 聚合列取值：MySQL 聚合按驱动返回 Long/BigDecimal 不等，统一 Number → int（NULL 视为 0） */
    private static int num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 祖先骨架（必要祖先，≤5 轮补拉、userId 过滤）：周期内任务的父链 + 周期外祖先，
     * 只取 (id, parentId, status, planDate) 四列——树形结构与游走决策用，明细列不在此读取。
     * 与历史「nodeMap 补拉」同一语义：parentId 指向他人/已删除任务 → 查不到 → 调用方落孤儿根。
     */
    private Map<Long, BizTask> loadAncestorSkeleton(Long userId, LocalDate startDate, LocalDate endDate) {
        Map<Long, BizTask> skeleton = new LinkedHashMap<>();
        Set<Long> pending = new LinkedHashSet<>();
        List<Map<String, Object>> parentRows = bizTaskMapper.selectMaps(Wrappers.<BizTask>query()
                .select("DISTINCT parent_id AS pid")
                .eq("user_id", userId)
                .isNotNull("parent_id")
                .ge("plan_date", startDate)
                .le("plan_date", endDate));
        for (Map<String, Object> r : parentRows) {
            Object pid = r.get("pid");
            if (pid instanceof Number n) {
                pending.add(n.longValue());
            }
        }
        for (int round = 0; round < 5 && !pending.isEmpty(); round++) {
            List<BizTask> rows = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                    .select(BizTask::getId, BizTask::getParentId, BizTask::getStatus, BizTask::getPlanDate)
                    .in(BizTask::getId, pending)
                    .eq(BizTask::getUserId, userId));
            if (rows == null || rows.isEmpty()) {
                break;
            }
            Set<Long> next = new LinkedHashSet<>();
            for (BizTask t : rows) {
                if (t.getId() != null && skeleton.putIfAbsent(t.getId(), t) == null
                        && t.getParentId() != null && !skeleton.containsKey(t.getParentId())) {
                    next.add(t.getParentId());
                }
            }
            pending = next;
        }
        return skeleton;
    }

    /**
     * 报告周期内、属于范围内任务的跟进记录（一次查询 LIMIT 200 兜底，按 taskId 分组）。
     * 归属限制：taskId 来自当前用户任务 + 补拉祖先，查询仍必须带 userId（纵深防御，防跨用户混入）。
     */
    private Map<Long, List<BizTaskLog>> fetchLogsGroupedByTask(Long userId, List<Long> taskIds, LocalDate start, LocalDate end) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<BizTaskLog> logs = bizTaskLogMapper.selectList(Wrappers.<BizTaskLog>lambdaQuery()
                .eq(BizTaskLog::getUserId, userId)
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
     * 决策遍历（游标分批加载子级，不渲染）：按既有预算规则确定「展开任务集」。
     * <p>预算规则与历史实现逐条一致：
     * <ul>
     *   <li>已完成且整棵子树无未完成任务 → 受「已完成预算」控制，超出只计数不展开；</li>
     *   <li>已完成但子树里有未完成任务（{@code containerIds}）→ 仍展开（层级容器，不占预算）；</li>
     *   <li>未完成任务 → 受 {@value UNFINISHED_CAP} 上限保护。</li>
     * </ul>
     * 被剪枝节点整棵子树不再访问（不展开、不计数）——子级经 {@link NodePager} 游标分批读取，
     * 剪枝子树一行不读。计数走 int[]（递归内共享），depth 硬上限防御异常数据成环。
     */
    private void planTaskTree(Long userId, BizTask node, int depth,
                              LocalDate startDate, LocalDate endDate,
                              Set<Long> skeletonIds, Set<Long> nullDateParents, Set<Long> containerIds,
                              int completedBudget, int[] seq,
                              int[] completedUsed, int[] unfinishedUsed,
                              int[] completedDropped, int[] unfinishedDropped,
                              Set<Long> expandedIds, Map<Long, Integer> seqById) {
        if (depth > 6) {
            return; // 防御：parentId 异常成环时终止递归
        }
        int status = node.getStatus() == null ? 0 : node.getStatus();
        boolean finished = status == 2;

        if (finished) {
            if (!containerIds.contains(node.getId())) {
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

        if (depth == 0) {
            seqById.put(node.getId(), seq[0]++);
        }
        expandedIds.add(node.getId());

        // 子级游标分批遍历（仅展开节点触发；剪枝子树不读取）
        NodePager pager = new NodePager(userId, node.getId(), startDate, endDate, skeletonIds,
                !nullDateParents.contains(node.getId()));
        while (true) {
            List<BizTask> page = pager.nextPage();
            if (page.isEmpty()) {
                break;
            }
            for (BizTask child : page) {
                planTaskTree(userId, child, depth + 1, startDate, endDate, skeletonIds, nullDateParents,
                        containerIds, completedBudget, seq, completedUsed, unfinishedUsed,
                        completedDropped, unfinishedDropped, expandedIds, seqById);
            }
        }
    }

    /** 展开集明细行（标题/正文/优先级/进度/工时/重点等渲染列）：只按决策遍历实际展开的任务读取 */
    private Map<Long, BizTask> fetchDetailRows(Long userId, Set<Long> expandedIds) {
        if (expandedIds.isEmpty()) {
            return Map.of();
        }
        List<BizTask> rows = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                .select(BizTask::getId, BizTask::getParentId, BizTask::getTitle, BizTask::getStatus,
                        BizTask::getPriority, BizTask::getProgress, BizTask::getPlanDate,
                        BizTask::getActualHours, BizTask::getIsKey, BizTask::getContent)
                .eq(BizTask::getUserId, userId)
                .in(BizTask::getId, expandedIds));
        Map<Long, BizTask> byId = new LinkedHashMap<>();
        for (BizTask t : rows) {
            byId.put(t.getId(), t);
        }
        return byId;
    }

    /**
     * 容器判定集：节点集内未完成任务的全部祖先 id ——「完成节点含未完成后代 → 恒展开（不占预算）」。
     * 与历史 {@code hasUnfinishedInSubtree} 递归同一语义，但沿父链上溯即可，无需展开子树。
     */
    private Set<Long> loadContainerIds(Long userId, LocalDate startDate, LocalDate endDate,
                                       Map<Long, BizTask> skeleton) {
        Set<Long> containers = new LinkedHashSet<>();
        Set<Long> pending = new LinkedHashSet<>();
        // 种子：节点集内未完成任务的父 id——周期内直接查，骨架中的未完成行内存过滤
        List<Map<String, Object>> seedRows = bizTaskMapper.selectMaps(Wrappers.<BizTask>query()
                .select("DISTINCT parent_id AS pid")
                .eq("user_id", userId)
                .isNotNull("parent_id")
                .ne("status", 2)
                .ge("plan_date", startDate)
                .le("plan_date", endDate));
        for (Map<String, Object> r : seedRows) {
            Object pid = r.get("pid");
            if (pid instanceof Number n) {
                pending.add(n.longValue());
            }
        }
        for (BizTask t : skeleton.values()) {
            if (t.getStatus() != null && t.getStatus() != 2 && t.getParentId() != null) {
                pending.add(t.getParentId());
            }
        }
        for (int round = 0; round < 10 && !pending.isEmpty(); round++) {
            Set<Long> needRows = new LinkedHashSet<>(); // 不在骨架中的链条段（周期内节点）→ 批量补查
            Set<Long> next = new LinkedHashSet<>();
            for (Long id : pending) {
                if (id == null || !containers.add(id)) {
                    continue;
                }
                BizTask skelRow = skeleton.get(id);
                if (skelRow != null) {
                    if (skelRow.getParentId() != null && !containers.contains(skelRow.getParentId())) {
                        next.add(skelRow.getParentId());
                    }
                } else {
                    needRows.add(id);
                }
            }
            if (!needRows.isEmpty()) {
                List<BizTask> rows = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                        .select(BizTask::getId, BizTask::getParentId)
                        .in(BizTask::getId, needRows)
                        .eq(BizTask::getUserId, userId));
                for (BizTask t : rows) {
                    if (t.getParentId() != null && !containers.contains(t.getParentId())) {
                        next.add(t.getParentId());
                    }
                }
            }
            pending = next;
        }
        return containers;
    }

    /** 节点游标分页批大小：根/子节点单次查询的行数上限 */
    private static final int NODE_PAGE_SIZE = 500;

    /**
     * 节点游标分页器：稳定排序 (plan_date, id)、plan_date 为空的行排最后（与历史 nullsLast 语义一致）。
     * 两段式 keyset 游标——非空段按 (plan_date, id) 推进、空值段按 id 接续；每页 ≤ {@value NODE_PAGE_SIZE} 行，
     * 一次绝不返回全部节点，也不逐行查询。骨架内存信息证明无空值行的分页跳过空值段（无空探测）。
     *
     * <p>范围过滤（报告周期 ∪ 祖先骨架）全部下推 SQL——不再读取范围外的历史子任务。
     * {@code parentId == null} = 根节点分页（父级不在节点集内，含周期外祖先的树顶）；
     * 否则为该父任务的子节点分页（仅展开节点触发）。
     */
    private final class NodePager {
        private final Long userId;
        private final Long parentId;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final Set<Long> skeletonIds;
        private final boolean skipNullPhase;
        private boolean nullPhase;
        private LocalDate cursorDate;
        private Long cursorId;
        private boolean exhausted;

        NodePager(Long userId, Long parentId, LocalDate startDate, LocalDate endDate,
                  Set<Long> skeletonIds, boolean skipNullPhase) {
            this.userId = userId;
            this.parentId = parentId;
            this.startDate = startDate;
            this.endDate = endDate;
            this.skeletonIds = skeletonIds;
            this.skipNullPhase = skipNullPhase;
        }

        /** 下一页（空列表 = 已到末尾）；每次查询最多 {@value NODE_PAGE_SIZE} 行 */
        List<BizTask> nextPage() {
            while (!exhausted) {
                LambdaQueryWrapper<BizTask> w = Wrappers.<BizTask>lambdaQuery()
                        .select(BizTask::getId, BizTask::getParentId, BizTask::getStatus, BizTask::getPlanDate)
                        .eq(BizTask::getUserId, userId);
                // 范围下推：报告周期 ∪ 祖先骨架（不读范围外历史子任务）
                if (skeletonIds.isEmpty()) {
                    w.between(BizTask::getPlanDate, startDate, endDate);
                } else {
                    w.and(x -> x.between(BizTask::getPlanDate, startDate, endDate)
                            .or().in(BizTask::getId, skeletonIds));
                }
                if (parentId == null) {
                    // 根 = 父级不在节点集（周期内 ∪ 骨架）内
                    String skelIn = skeletonIds.isEmpty() ? ""
                            : " OR p.id IN (" + skeletonIds.stream().map(String::valueOf)
                                    .collect(Collectors.joining(",")) + ")";
                    w.apply("(biz_task.parent_id IS NULL OR NOT EXISTS (SELECT 1 FROM biz_task p"
                                    + " WHERE p.id = biz_task.parent_id AND p.user_id = {0} AND "
                                    + "(p.plan_date BETWEEN {1} AND {2}" + skelIn + ")))",
                            userId, startDate, endDate);
                } else {
                    w.eq(BizTask::getParentId, parentId);
                }
                if (!nullPhase) {
                    // 非空段：keyset 游标 (plan_date, id)——索引友好
                    w.isNotNull(BizTask::getPlanDate);
                    if (cursorDate != null) {
                        w.apply("(biz_task.plan_date > {0} OR (biz_task.plan_date = {0} AND biz_task.id > {1}))",
                                cursorDate, cursorId);
                    }
                    w.last("ORDER BY biz_task.plan_date ASC, biz_task.id ASC LIMIT " + NODE_PAGE_SIZE);
                } else {
                    // 空值段：plan_date IS NULL 的行按 id 接续（排最后）
                    w.isNull(BizTask::getPlanDate);
                    if (cursorId != null) {
                        w.gt(BizTask::getId, cursorId);
                    }
                    w.last("ORDER BY biz_task.id ASC LIMIT " + NODE_PAGE_SIZE);
                }
                List<BizTask> rows = bizTaskMapper.selectList(w);
                if (rows.size() >= NODE_PAGE_SIZE) {
                    BizTask last = rows.get(rows.size() - 1);
                    cursorDate = nullPhase ? null : last.getPlanDate();
                    cursorId = last.getId();
                    return rows;
                }
                // 短页 = 当前段到尾
                if (!nullPhase && !skipNullPhase) {
                    nullPhase = true;
                    cursorDate = null;
                    cursorId = null;
                    if (!rows.isEmpty()) {
                        return rows; // 非空段尾页交付后，下一次调用进入空值段
                    }
                    continue; // 空页直接探空值段
                }
                exhausted = true;
                return rows;
            }
            return List.of();
        }
    }

    /**
     * 渲染遍历（在决策遍历之后执行）：只渲染展开集内的节点，任务预算不再扣减（决策遍历已消费）；
     * 跟进记录预算在渲染时扣减（与原单遍行为一致）。被丢弃节点整棵子树不渲染（与原 early-return 一致）。
     */
    private void renderTaskTree(StringBuilder out, BizTask node, int depth, boolean orphanRoot,
                                Map<Long, List<BizTask>> childrenMap, Map<Long, List<BizTaskLog>> logsByTask,
                                Map<Long, String> contentById, Set<Long> expandedIds,
                                Map<Long, Integer> seqById, int logTotalBudget, int[] logsUsed) {
        if (depth > 6) {
            return; // 防御：与决策遍历同款终止条件
        }
        if (!expandedIds.contains(node.getId())) {
            return; // 决策遍历已丢弃（丢弃计数在决策遍历完成）：子树整体不渲染
        }

        // 任务行：顶层带序号（序号在决策遍历已分配），子任务按层级缩进
        String bullet = depth == 0 ? (seqById.getOrDefault(node.getId(), 0) + 1) + ". "
                : (depth == 1 ? "  - " : "    · ");
        out.append(bullet).append(taskLine(node, depth, orphanRoot)).append('\n');

        String desc = clip(contentById.get(node.getId()), 60);
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
            renderTaskTree(out, child, depth + 1, false, childrenMap, logsByTask,
                    contentById, expandedIds, seqById, logTotalBudget, logsUsed);
        }
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

        // ① 数据准备 + Prompt 构建（与同步路径共用 buildPrompt：
        //    完整统计走数据库聚合、明细/跟进按 Prompt 预算限量读取，不再整段载入周期内任务）
        String prompt = buildPrompt(userId, req.reportType(), req.startDate(), req.endDate());

        // ③ 落库对象先备好，流结束后补 content 再 insert
        BizReport report = new BizReport();
        report.setUserId(userId);
        report.setReportType(req.reportType());
        report.setStartDate(req.startDate());
        report.setEndDate(req.endDate());
        report.setAiModel(model);
        report.setCreateTime(LocalDateTime.now());

        // ④ 大模型流式调用与 SSE 推送在独立线程执行（有界线程池，满员快速拒绝）
        InflightGeneration inflight = new InflightGeneration(sseExecutor);
        // 客户端断开 / SSE 超时 / 连接异常 → 取消任务并关闭上游模型流；
        // 任务里的取消检查与部分内容保全（savePartialOnFailure）负责收尾。
        emitter.onCompletion(inflight::cancel);
        emitter.onError(t -> inflight.cancel());
        emitter.onTimeout(() -> {
            log.warn("SSE 报告生成超时，取消任务: userId={}", userId);
            inflight.cancel();
        });

        Future<?> future;
        try {
            future = sseExecutor.submit(() -> {
                if (inflight.isCancelled()) {
                    return; // 排队期间已被取消：不调 AI、立即让出（排队容量已由 cancel 移除释放）
                }
                inFlight.add(inflight);
                try {
                    doStreamGenerate(report, prompt, emitter, inflight);
                } finally {
                    inFlight.remove(inflight);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：走既有 SSE error 事件明确提示并结束连接（不能抛给全局异常处理器）
            log.warn("SSE 报告生成队列已满，快速拒绝: userId={}", userId);
            sendErrorEvent(emitter, "服务繁忙，请稍后重试");
            return;
        }
        // 登记若发生在取消之后（连接已断/已超时的极小窗口），registerFuture 会立即补偿取消
        inflight.registerFuture(future);
    }

    /**
     * 生成任务体（在 SSE 线程池中执行）：流式调用大模型并实时推送，结束落库；
     * 失败/取消时保全已生成的部分内容（既有行为）。
     */
    private void doStreamGenerate(BizReport report, String prompt, SseEmitter emitter,
                                  InflightGeneration inflight) {
        StringBuilder markdown = new StringBuilder();
        try {
            streamChatModel(prompt, chunk -> {
                if (inflight.isCancelled() || Thread.currentThread().isInterrupted()) {
                    // 任务已被取消（客户端断开/超时/应用关闭）：停止消费，上游流由取消方关闭
                    throw new IllegalStateException("任务已取消");
                }
                markdown.append(chunk);
                try {
                    // ★ 每收到一块数据，实时推送给前端
                    emitter.send(SseEmitter.event().data(chunk));
                } catch (IOException e) {
                    // 推送失败（客户端断开等）→ 抛运行时异常，由下方统一走 error 事件
                    throw new IllegalStateException("SSE 推送失败，客户端可能已断开", e);
                }
            }, outputBudget(report.getReportType()), inflight);

            report.setContent(markdown.toString());
            bizReportMapper.insert(report);

            emitter.send(SseEmitter.event()
                    .name("done").data(Map.of("reportId", report.getId())));
            emitter.complete(); // ★ 传输完毕，正常结束
        } catch (Exception e) {
            boolean wasCancelled = Thread.interrupted(); // 清除取消中断标记，防污染线程池后续任务
            // ★ 流中断时，已生成的部分内容也落库（附中断标记），用户等待不白费
            Long partialId = savePartialOnFailure(report, markdown);
            String message = partialId != null
                    ? "生成中断，已保存部分内容（可在历史记录查看）"
                    : "报告生成失败: " + e.getMessage();
            if (wasCancelled) {
                log.info("报告生成已取消（客户端断开/超时/应用关闭）: userId={}（partialId={}）",
                        report.getUserId(), partialId);
            } else {
                log.warn("报告生成中断/失败: {}（partialId={}）", e.getMessage(), partialId);
            }
            sendErrorEvent(emitter, message, partialId);
        }
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
    private void streamChatModel(String prompt, ChunkConsumer onChunk, int maxTokens,
                                 InflightGeneration inflight) {
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

        // ★ 响应体流关闭统一收口：非 200 / 解析失败 / 中断 / 正常结束 全部经 try-with-resources 关闭
        //   （send 阶段失败尚无响应体，无流可关）。
        try (Stream<String> lines = response.body()) {
            try {
                // 上游流句柄交取消方托管（登记前已被取消则立即补偿关闭）
                inflight.registerUpstream(lines);
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("大模型返回 HTTP " + response.statusCode()
                            + "（检查 base-url / api-key / model 配置）");
                }
                boolean finished = false;
                for (Iterator<String> it = lines.iterator(); it.hasNext() && !finished; ) {
                    if (inflight.isCancelled() || Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("任务已取消（上游流将被关闭）");
                    }
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
                if (inflight.isCancelled() || Thread.currentThread().isInterrupted()) {
                    // 取消恰好落在流自然结束的间隙：也不能按"成功"落库
                    throw new IllegalStateException("任务已取消");
                }
            } finally {
                inflight.clearUpstream(lines); // 流已关闭（无论正常/异常/被取消方关闭），句柄清空
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
