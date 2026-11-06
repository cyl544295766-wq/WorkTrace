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
 * 接口本身恒成功——前端保留本地规则文案作为网络层失败的兜底。
 *
 * <p>模型调用沿用 {@code ReportService} 的 Chat Completions 模式（RestTemplate + Bearer +
 * {@code chat/completions}），独立实现不与报告生成共用类，配置复用
 * {@code ai.base-url / ai.api-key / ai.model}，零新增配置。
 *
 * <p>缓存：Caffeine 按 userId 缓存 <b>AI</b> 结果 30 分钟（单机部署，参照 IdempotentAspect）；
 * RULES 结果不缓存，下次请求自动重试 AI。
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
                    + "语气专业、具体、不说空话，结合统计里的逾期、到期、优先级等实际情况。";

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
     * 获取工作洞察：缓存命中直接返回；未命中统计任务 → 调 AI；失败降级规则文案。
     */
    public InsightVO getWorkInsight(Long userId) {
        InsightVO cached = cache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }

        List<BizTask> tasks = bizTaskMapper.selectList(Wrappers.<BizTask>lambdaQuery()
                .eq(BizTask::getUserId, userId));
        Stats stats = Stats.of(tasks);
        if (stats.total == 0) {
            return rulesInsight(stats); // 空态引导，不调 AI
        }

        try {
            InsightVO vo = parseInsight(callChatModel(buildPrompt(stats)));
            cache.put(userId, vo); // 只缓存 AI 结果；RULES 不缓存，下次进入自动重试
            return vo;
        } catch (Exception e) {
            log.warn("AI 洞察生成失败，降级为规则文案: {}", e.getMessage());
            return rulesInsight(stats);
        }
    }

    /* ==================== 统计聚合 ==================== */

    /** 任务统计快照（供 prompt 与规则兜底共用；个人任务量级在内存聚合足够） */
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
        double avgProgress; // 平均进度（0-100）
        Map<String, Integer> categories = new LinkedHashMap<>(); // 分类分布（按数量降序，≤6）
        List<String> pendingSamples = new ArrayList<>();          // 未完成任务标题样例（≤10）

        static Stats of(List<BizTask> tasks) {
            Stats s = new Stats();
            s.total = tasks.size();
            LocalDate today = LocalDate.now();
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            long progressSum = 0;
            Map<String, Integer> cats = new LinkedHashMap<>();
            for (BizTask t : tasks) {
                int st = t.getStatus() == null ? 0 : t.getStatus();
                if (st == 2) {
                    s.done++;
                    if (t.getFinishTime() != null && t.getFinishTime().isAfter(weekAgo)) {
                        s.doneLast7++;
                    }
                } else if (st == 1) {
                    s.doing++;
                } else if (st == 3) {
                    s.delayed++;
                } else {
                    s.todo++;
                }

                boolean unfinished = st != 2;
                if (unfinished && t.getPlanDate() != null) {
                    if (t.getPlanDate().isBefore(today)) s.overdue++;
                    else if (t.getPlanDate().equals(today)) s.dueToday++;
                    if (!t.getPlanDate().isBefore(today) && t.getPlanDate().isBefore(today.plusDays(7))) {
                        s.dueWeek++;
                    }
                }
                if (unfinished && t.getPriority() != null && t.getPriority() == 3) {
                    s.highPending++;
                }
                if (t.getIsKey() != null && t.getIsKey() == 1) {
                    s.keyTasks++;
                }
                if (t.getProgress() != null) {
                    progressSum += t.getProgress();
                }
                if (t.getCategory() != null && !t.getCategory().isBlank()) {
                    cats.merge(t.getCategory(), 1, Integer::sum);
                }
                if (unfinished && s.pendingSamples.size() < 10
                        && t.getTitle() != null && !t.getTitle().isBlank()) {
                    s.pendingSamples.add(t.getTitle());
                }
            }
            s.avgProgress = s.total == 0 ? 0 : Math.round(progressSum * 10.0 / s.total) / 10.0;
            cats.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(6)
                    .forEach(e -> s.categories.put(e.getKey(), e.getValue()));
            return s;
        }
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
