package com.worktrace.insight.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.worktrace.insight.dto.InsightVO;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.mapper.BizTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工作洞察：基于当前用户的真实任务统计调用大模型生成简短洞察文案。
 *
 * <p><b>降级策略（关键）</b>：AI key 缺失 / 超时 / 响应不可解析时返回规则兜底文案（source=RULES），
 * 接口本身恒成功——前端保留本地规则文案作为网络层失败的兜底。瞬时失败自动重试 1 次（约 400ms 后）。
 *
 * <p>模型调用沿用 {@code ReportService} 的 Chat Completions 模式（RestTemplate + Bearer +
 * {@code chat/completions}），独立实现不与报告生成共用类，配置复用
 * {@code ai.base-url / ai.api-key / ai.model}，零新增配置。
 *
 * <p>缓存：Caffeine 按 userId 缓存 <b>AI</b> 结果 30 分钟（单机部署，参照 IdempotentAspect）；
 * RULES 结果不缓存，下次请求自动重试 AI。首页「换一条」走 {@code refresh=true} 绕过缓存
 * 重新生成（成功后回写缓存）。
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    /** system 消息：严格 JSON 输出，长度约束贴合首页卡片排版 */
    private static final String SYSTEM_PROMPT =
            "你是工作管理助手。根据用户任务统计生成简短的中文工作洞察。"
                    + "只输出一个 JSON 对象，不要输出任何其他文字或 Markdown 代码围栏，格式："
                    + "{\"headline\":\"不超过12字的短标题\",\"para\":\"不超过80字的分析\","
                    + "\"advice\":\"不超过18字的行动建议\"}。"
                    + "语气专业、具体、不说空话，结合统计里的逾期、到期、优先级等实际情况；"
                    + "统计里有近 7 天完成数与再前 7 天完成数的对比时，用一句话点出节奏变化（加快/放缓/持平）。";

    /** category 代码 → 中文（只影响 prompt 可读性） */
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "FEATURE", "新增需求", "BUG", "bug修改", "DATA", "数据处理",
            "JOINT", "联调对接", "DOC", "文档", "OPS", "部署运维", "OTHER", "其他");

    private final BizTaskMapper bizTaskMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ai.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.model:deepseek-chat}")
    private String model;

    /** AI 洞察结果缓存：userId → InsightVO */
    private final Cache<Long, InsightVO> cache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public InsightService(BizTaskMapper bizTaskMapper, ObjectMapper objectMapper) {
        this.bizTaskMapper = bizTaskMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate();
    }

    /** 连接 5s / 读取 12s：洞察是轻量一句话，超时快降级（须小于前端 axios 15s 超时） */
    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(12_000);
        return new RestTemplate(factory);
    }

    /**
     * 获取工作洞察：缓存命中直接返回；未命中统计任务 → 调 AI（失败重试 1 次）；失败降级规则文案。
     *
     * @param refresh true = 跳过缓存读取重新生成（首页「换一条」），成功后回写缓存
     */
    public InsightVO getWorkInsight(Long userId, boolean refresh) {
        if (!refresh) {
            InsightVO cached = cache.getIfPresent(userId);
            if (cached != null) {
                return cached;
            }
        }

        Stats stats = loadStats(userId);
        if (stats.total == 0) {
            return rulesInsight(stats); // 空态引导，不调 AI
        }

        // 瞬时失败（网络抖动/超时）自动重试 1 次；仍失败才降级规则文案
        Exception lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                InsightVO vo = parseInsight(callChatModel(buildPrompt(stats)));
                cache.put(userId, vo); // 只缓存 AI 结果；RULES 不缓存，下次进入自动重试
                return vo;
            } catch (Exception e) {
                lastError = e;
                if (attempt == 1) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("AI 洞察生成失败（含 1 次重试），降级为规则文案: {}",
                lastError == null ? "unknown" : lastError.getMessage());
        return rulesInsight(stats);
    }

    /* ==================== 统计聚合（数据库聚合，替代原全量行加载 + 内存统计） ==================== */

    /**
     * 任务统计快照（供 prompt 与规则兜底共用；口径与原内存版完全一致）。
     * 由 3 条轻查询填充：①单行条件聚合 ②分类分布（≤6 行）③未完成标题样例（≤10 行，仅 title 列）——
     * 不再把该用户全部任务行（含 content 大字段）载入内存。
     */
    private static final class Stats {
        int total;          // 任务总数（全节点）
        int done;           // 已完成
        int doing;          // 进行中
        int todo;           // 待处理
        int delayed;        // 已延期
        int overdue;        // 逾期：计划日期已过且未完成
        int dueToday;       // 今日到期且未完成
        int dueWeek;        // 未来 7 天内到期且未完成
        int highPending;    // 高优未完成
        int keyTasks;       // 重点标记（is_key=1）
        int doneLast7;      // 近 7 天完成
        int donePrev7;      // 再前 7 天完成（对比节奏：与 doneLast7 构成环比）
        int newLast7;       // 近 7 天新增（createTime 口径）
        double avgProgress; // 平均进度（0-100）
        Map<String, Integer> categories = new LinkedHashMap<>(); // 分类分布（按数量降序，≤6）
        List<String> pendingSamples = new ArrayList<>();          // 未完成任务标题样例（≤10）
    }

    /**
     * 数据库聚合统计。时间边界（今天 / 7 天 / 14 天）全部由应用端生成后作为字面量拼入
     * 聚合表达式——与原 Java 内存版用同一时钟、同一比较语义，统计口径不变；
     * 值均为系统时钟生成，无用户输入，不存在注入面。
     */
    private Stats loadStats(Long userId) {
        Stats s = new Stats();
        LocalDate today = LocalDate.now();
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime twoWeeksAgo = LocalDateTime.now().minusDays(14);
        String d = "'" + today + "'";
        String w = "'" + weekAgo.toString().replace('T', ' ') + "'";
        String w2 = "'" + twoWeeksAgo.toString().replace('T', ' ') + "'";

        // ① 单行条件聚合：状态分布 / 逾期 / 到期 / 高优 / 重点 / 完成节奏 / 新增 / 进度和
        //    口径对应原内存版：todo = 非(1/2/3)（status 列 NOT NULL，恒有值）；
        //    未完成 = status<>2；done*7 按 finish_time 边界；avgProgress = SUM(progress)/任务总数。
        List<Map<String, Object>> aggRows = bizTaskMapper.selectMaps(Wrappers.<BizTask>query()
                .select(
                        "COUNT(*) AS total",
                        "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS done_cnt",
                        "SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS doing_cnt",
                        "SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END) AS delayed_cnt",
                        "SUM(CASE WHEN status <> 2 AND plan_date < " + d + " THEN 1 ELSE 0 END) AS overdue",
                        "SUM(CASE WHEN status <> 2 AND plan_date = " + d + " THEN 1 ELSE 0 END) AS due_today",
                        "SUM(CASE WHEN status <> 2 AND plan_date >= " + d
                                + " AND plan_date < '" + today.plusDays(7) + "' THEN 1 ELSE 0 END) AS due_week",
                        "SUM(CASE WHEN status <> 2 AND priority = 3 THEN 1 ELSE 0 END) AS high_pending",
                        "SUM(CASE WHEN is_key = 1 THEN 1 ELSE 0 END) AS key_tasks",
                        "SUM(CASE WHEN status = 2 AND finish_time > " + w + " THEN 1 ELSE 0 END) AS done_last7",
                        "SUM(CASE WHEN status = 2 AND finish_time > " + w2
                                + " AND finish_time <= " + w + " THEN 1 ELSE 0 END) AS done_prev7",
                        "SUM(CASE WHEN create_time > " + w + " THEN 1 ELSE 0 END) AS new_last7",
                        "COALESCE(SUM(progress), 0) AS progress_sum")
                .eq("user_id", userId));
        Map<String, Object> row = aggRows == null || aggRows.isEmpty() ? Map.of() : aggRows.get(0);
        if (row == null || row.isEmpty()) {
            return s; // 无任务（理论不可达：无 GROUP BY 的聚合恒返回一行）
        }
        s.total = num(row, "total");
        s.done = num(row, "done_cnt");
        s.doing = num(row, "doing_cnt");
        s.delayed = num(row, "delayed_cnt");
        s.todo = s.total - s.done - s.doing - s.delayed; // 与原 else 分支口径一致
        s.overdue = num(row, "overdue");
        s.dueToday = num(row, "due_today");
        s.dueWeek = num(row, "due_week");
        s.highPending = num(row, "high_pending");
        s.keyTasks = num(row, "key_tasks");
        s.doneLast7 = num(row, "done_last7");
        s.donePrev7 = num(row, "done_prev7");
        s.newLast7 = num(row, "new_last7");
        double progressSum = dbl(row, "progress_sum");
        s.avgProgress = s.total == 0 ? 0 : Math.round(progressSum * 10.0 / s.total) / 10.0;

        // ② 分类分布：按数量降序取前 6（原口径：非空分类计数、降序、≤6）
        List<Map<String, Object>> catRows = bizTaskMapper.selectMaps(Wrappers.<BizTask>query()
                .select("category", "COUNT(*) AS cnt")
                .eq("user_id", userId)
                .isNotNull("category")
                .apply("TRIM(category) <> ''")
                .groupBy("category")
                .last("ORDER BY cnt DESC LIMIT 6"));
        for (Map<String, Object> cr : catRows) {
            Object cat = cr.get("category");
            if (cat != null) {
                s.categories.put(String.valueOf(cat), num(cr, "cnt"));
            }
        }

        // ③ 未完成任务标题样例：≤10 条（原口径：未完成 + 标题非空白，取前 10）
        List<BizTask> samples = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                .select(BizTask::getTitle)
                .eq(BizTask::getUserId, userId)
                .ne(BizTask::getStatus, 2)
                .isNotNull(BizTask::getTitle)
                .apply("TRIM(title) <> ''")
                .last("LIMIT 10"));
        for (BizTask t : samples) {
            if (t.getTitle() != null && !t.getTitle().isBlank()) {
                s.pendingSamples.add(t.getTitle());
            }
        }
        return s;
    }

    /** selectMaps 数值取值：MySQL 聚合列按驱动返回 Long/BigDecimal 不等，统一 Number → int（NULL 视为 0） */
    private static int num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double dbl(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /* ==================== Prompt 与解析 ==================== */

    /** 任务统计 → 结构化 Prompt */
    private String buildPrompt(Stats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("我的任务统计（").append(LocalDate.now()).append("）：\n");
        sb.append("- 任务总数 ").append(s.total)
                .append("，已完成 ").append(s.done)
                .append("，进行中 ").append(s.doing)
                .append("，待处理 ").append(s.todo)
                .append("，已延期 ").append(s.delayed).append('\n');
        sb.append("- 逾期 ").append(s.overdue)
                .append("（计划日期已过且未完成）；今日到期 ").append(s.dueToday)
                .append("；未来 7 天到期 ").append(s.dueWeek).append('\n');
        sb.append("- 高优先级未完成 ").append(s.highPending)
                .append("；近 7 天完成 ").append(s.doneLast7)
                .append("（再前 7 天完成 ").append(s.donePrev7).append("）")
                .append("；近 7 天新增 ").append(s.newLast7)
                .append("；平均进度 ").append(s.avgProgress).append("%\n");
        sb.append("- 重点任务（用户手动标记）").append(s.keyTasks).append(" 条\n");
        if (!s.categories.isEmpty()) {
            sb.append("- 分类分布：");
            List<String> parts = new ArrayList<>();
            s.categories.forEach((k, v) -> parts.add(CATEGORY_LABEL.getOrDefault(k, k) + " " + v));
            sb.append(String.join("，", parts)).append('\n');
        }
        if (!s.pendingSamples.isEmpty()) {
            sb.append("- 未完成任务：");
            List<String> parts = new ArrayList<>();
            for (String title : s.pendingSamples) {
                parts.add("《" + title + "》");
            }
            sb.append(String.join(" ", parts)).append('\n');
        }
        sb.append("\n请根据以上统计生成工作洞察 JSON。");
        return sb.toString();
    }

    /**
     * 调用 OpenAI / DeepSeek 兼容的 Chat Completions，返回 message.content 原文。
     *
     * @throws IllegalStateException 未配置 apiKey / 返回为空 / 响应不可解析
     */
    private String callChatModel(String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 AI api-key（application.yml 的 ai.api-key 或环境变量 AI_API_KEY）");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)));
        requestBody.put("temperature", 0.6);
        // 洞察是三段短文案（12+80+18 字），220 token 足够且硬性控成本
        requestBody.put("max_tokens", 220);

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

    /** 模型输出 → InsightVO：剥离 ``` 围栏、提取首个 {...} 块，字段缺失即抛错走兜底 */
    private InsightVO parseInsight(String content) throws JsonProcessingException {
        String s = content.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("洞察输出不含 JSON: " + content);
        }
        JsonNode node = objectMapper.readTree(s.substring(start, end + 1));
        String headline = node.path("headline").asText("").trim();
        String para = node.path("para").asText("").trim();
        String advice = node.path("advice").asText("").trim();
        if (headline.isEmpty() || para.isEmpty() || advice.isEmpty()) {
            throw new IllegalStateException("洞察 JSON 字段缺失: " + content);
        }
        return new InsightVO(headline, para, advice, InsightVO.SOURCE_AI);
    }

    /* ==================== 规则兜底（与前端本地文案一致） ==================== */

    /** 按完成率分档的兜底文案，档位与文案与 Home.vue 的 fallbackInsight 保持一致 */
    private InsightVO rulesInsight(Stats s) {
        if (s.total == 0) {
            return new InsightVO("从第一件任务开始吧",
                    "还没有任务记录，点击右上角「新增任务」开始记录你的工作轨迹。",
                    "万事开头难，先建一个小目标！", InsightVO.SOURCE_RULES);
        }
        double rate = (double) s.done / s.total;
        if (rate >= 0.6) {
            return new InsightVO("今日工作状态良好",
                    "近期任务完成情况稳定，建议优先处理临近截止的任务，合理安排后续工作计划。",
                    "保持当前节奏，继续高效推进！", InsightVO.SOURCE_RULES);
        }
        if (rate >= 0.2) {
            return new InsightVO("任务推进中，注意节奏",
                    "部分任务仍在进行中，建议聚焦高优先级事项，为临近截止的任务预留缓冲时间。",
                    "抓大放小，优先攻克高优任务！", InsightVO.SOURCE_RULES);
        }
        return new InsightVO("任务积压，需要加速",
                "待处理任务占比较高，建议今天先清理低优与过期事项，重新排定本周计划。",
                "先清一批待办，再规划新任务！", InsightVO.SOURCE_RULES);
    }
}
