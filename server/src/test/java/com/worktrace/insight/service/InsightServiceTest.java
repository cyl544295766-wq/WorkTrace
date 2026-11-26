package com.worktrace.insight.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.worktrace.common.api.Result;
import com.worktrace.common.exception.BizException;
import com.worktrace.insight.dto.InsightVO;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.mapper.BizTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 全部 SQL 和模型响应均在内存模拟，不连接数据库或 AI 服务。 */
class InsightServiceTest {
    private static final String URL = "http://insight.test/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, Fixture> fixtures = new HashMap<>();
    private final List<Long> queriedUsers = new ArrayList<>();
    private InsightService service;
    private MockRestServiceServer model;

    @BeforeAll
    static void initTable() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "insight-test"), BizTask.class);
    }

    @BeforeEach
    void setUp() {
        fixtures.put(1L, new Fixture());
        BizTaskMapper mapper = mock(BizTaskMapper.class);
        when(mapper.selectMaps(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            Fixture f = fixtureFor(w);
            String select = w.getSqlSelect();
            if (select.contains("COUNT(*) AS total")) return List.of(f.aggregate());
            assertTrue(select.contains("unfinished_cnt"), "分类必须同时统计累计数与未完成数");
            return f.total == 0 ? List.of() : List.of(Map.of("category", "FEATURE", "cnt", f.categoryTotal,
                    "unfinished_cnt", f.categoryUnfinished));
        });
        when(mapper.selectList(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            fixtureFor(w);
            return List.of();
        });
        service = new InsightService(mapper, json);
        ReflectionTestUtils.setField(service, "apiKey", "local-test-only");
        ReflectionTestUtils.setField(service, "baseUrl", "http://insight.test");
        ReflectionTestUtils.setField(service, "model", "mock-model");
        RestTemplate rest = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        model = MockRestServiceServer.bindTo(rest).build();
    }

    private Fixture fixtureFor(AbstractWrapper<?, ?, ?> w) {
        assertTrue(w.getSqlSegment().contains("user_id"), "每条统计/样例查询必须按用户过滤");
        assertTrue(w.getSqlSegment().contains("task_scope"), "每条统计/样例查询必须按任务范围过滤");
        Long userId = w.getParamNameValuePairs().values().stream().filter(Long.class::isInstance)
                .map(Long.class::cast).findFirst().orElseThrow();
        queriedUsers.add(userId);
        return fixtures.get(userId);
    }

    private String response(String headline, String para, String advice) throws Exception {
        String content = json.writeValueAsString(Map.of("headline", headline, "para", para, "advice", advice));
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
    }

    private void expectValid() throws Exception {
        model.expect(requestTo(URL)).andRespond(withSuccess(response("优先推进关键事项",
                "先核对逾期原因，再安排高优任务", "明确负责人与处理计划"), MediaType.APPLICATION_JSON));
    }

    @Test
    void separatesCategoryTotalsFromUnfinishedAndCreationCounts() throws Exception {
        model.expect(requestTo(URL)).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
            String prompt = json.readTree(body).path("messages").path(1).path("content").asText();
            assertTrue(prompt.contains("新增需求：累计=110，未完成=21"));
            assertTrue(prompt.contains("未完成 23"));
            assertTrue(prompt.contains("首页待处理（含延期）18"));
            assertTrue(prompt.contains("近 7 天新增 6"));
            assertTrue(prompt.contains("今日新增 1"));
        }).andRespond(withSuccess(response("优先推进关键事项", "先核对逾期原因，再安排高优任务",
                "明确负责人与处理计划"), MediaType.APPLICATION_JSON));

        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_AI, result.source());
        assertTrue(result.para().contains("共115项，已完成92项，未完成23项"));
        assertFalse(result.para().contains("110"));
        assertEquals(18, result.stats().pending());
        assertEquals(5, result.stats().doing());
        assertEquals(23, result.stats().unfinished());
        assertEquals(1, result.stats().todayNew());
        assertEquals(80, result.stats().completionRate());
        assertEquals(LocalDate.now(), result.stats().date());
        model.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"新增需求110条积压", "仍有一百一十项积压", "完成率为99%", "已有23项完成", "仍有１１０项积压"})
    void rejectsModelQuantitiesInsteadOfReusingCategoryOrWrongMetric(String text) throws Exception {
        model.expect(requestTo(URL)).andRespond(withSuccess(response("需要关注工作节奏", text,
                "优先处理逾期任务"), MediaType.APPLICATION_JSON));
        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_RULES, result.source());
        assertTrue(result.para().contains("已完成92项，未完成23项"));
        assertTrue(result.para().contains("逾期11项"));
        assertFalse(result.para().contains(text));
        model.verify(); // 无额外模型重试
    }

    @Test
    void checksQuantitiesInHeadlineAndAdviceToo() throws Exception {
        model.expect(requestTo(URL)).andRespond(withSuccess(response("还有110项待办", "请按优先级推进工作",
                "先处理110项任务"), MediaType.APPLICATION_JSON));
        assertEquals(InsightVO.SOURCE_RULES, service.getWorkInsight(1L, "COMPANY", false).source());
        model.verify();
    }

    @Test
    void largeCategoryWithOnlyOneUnfinishedTaskIsNotBacklog() throws Exception {
        Fixture f = fixtures.get(1L);
        f.done = 114;
        f.doing = 1;
        f.delayed = f.overdue = f.highPending = 0;
        f.categoryUnfinished = 1;
        model.expect(requestTo(URL)).andRespond(withSuccess(response("需要关注工作节奏", "新增需求110条积压",
                "优先核对待办事项"), MediaType.APPLICATION_JSON));
        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_RULES, result.source());
        assertTrue(result.para().contains("已完成114项，未完成1项"));
        assertFalse(result.para().contains("积压"));
        model.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{invalid}", "{\"headline\":7,\"para\":\"关注工作进度\",\"advice\":\"安排后续计划\"}"})
    void malformedOrNonTextualOutputUsesRules(String content) throws Exception {
        String body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        model.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_RULES, result.source());
        assertTrue(result.para().contains("已完成92项，未完成23项"));
        model.verify();
    }

    @Test
    void allCompletedDoesNotBecomeBacklog() throws Exception {
        Fixture f = fixtures.get(1L);
        f.done = f.total;
        f.doing = f.delayed = f.overdue = f.highPending = f.categoryUnfinished = 0;
        model.expect(requestTo(URL)).andRespond(withSuccess(response("需要关注任务积压", "仍有110项积压",
                "优先清理积压任务"), MediaType.APPLICATION_JSON));
        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_RULES, result.source());
        assertEquals("任务已全部完成", result.headline());
        assertTrue(result.para().contains("已完成115项，未完成0项"));
        assertEquals(0, result.stats().pending());
        assertEquals(100, result.stats().completionRate());
        model.verify();
    }

    @Test
    void emptyTasksNeverCallModel() {
        Fixture f = fixtures.get(1L);
        f.total = f.done = f.doing = f.delayed = f.overdue = f.highPending = f.todayNew = f.keyTasks = 0;
        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_RULES, result.source());
        assertTrue(result.para().contains("共0项，已完成0项，未完成0项"));
        assertEquals(0, result.stats().completionRate());
        model.verify();
    }

    @Test
    void usesCacheOnlyWhileCurrentStatsMatch() throws Exception {
        expectValid();
        expectValid();
        InsightVO first = service.getWorkInsight(1L, "COMPANY", false);
        assertSame(first, service.getWorkInsight(1L, "COMPANY", false));
        Fixture f = fixtures.get(1L);
        f.done++;
        f.doing--;
        InsightVO next = service.getWorkInsight(1L, "COMPANY", false);
        assertNotSame(first, next);
        assertTrue(next.para().contains("已完成93项，未完成22项"));
        assertSame(next, service.getWorkInsight(1L, "COMPANY", false));
        model.verify();
    }

    @Test
    void cachesDoNotCrossUsersEvenWithIdenticalStats() throws Exception {
        fixtures.put(2L, new Fixture());
        expectValid();
        expectValid();
        InsightVO a = service.getWorkInsight(1L, "COMPANY", false);
        InsightVO b = service.getWorkInsight(2L, "COMPANY", false);
        assertNotSame(a, b);
        assertSame(a, service.getWorkInsight(1L, "COMPANY", false));
        assertSame(b, service.getWorkInsight(2L, "COMPANY", false));
        assertTrue(queriedUsers.contains(1L));
        assertTrue(queriedUsers.contains(2L));
        model.verify();
    }

    @Test
    void cachesDoNotCrossScopesEvenWithIdenticalStats() throws Exception {
        // 同一用户、同一统计：范围进入 prompt 与缓存键，公司/个人各自独立调用与命中
        // （MockRestServiceServer 要求先声明全部期望，再发请求）
        expectValid();
        expectValid();
        InsightVO company = service.getWorkInsight(1L, "COMPANY", false);
        InsightVO personal = service.getWorkInsight(1L, "PERSONAL", false);
        assertNotSame(company, personal, "不同范围不得共用缓存文案");
        assertSame(company, service.getWorkInsight(1L, "COMPANY", false));
        assertSame(personal, service.getWorkInsight(1L, "PERSONAL", false));
    }

    @Test
    void invalidScopeIsRejectedWithBadRequest() {
        BizException e = assertThrows(BizException.class,
                () -> service.getWorkInsight(1L, "SHARED", false));
        assertEquals(Result.BAD_REQUEST, e.getCode());
    }

    @Test
    void failedRefreshCannotResurrectOldCacheAndRulesAreNotCached() throws Exception {
        expectValid();
        model.expect(requestTo(URL)).andRespond(withSuccess(response("需要关注工作节奏", "积压110项任务",
                "请优先处理逾期任务"), MediaType.APPLICATION_JSON));
        expectValid();
        InsightVO first = service.getWorkInsight(1L, "COMPANY", false);
        assertEquals(InsightVO.SOURCE_RULES, service.getWorkInsight(1L, "COMPANY", true).source());
        InsightVO next = service.getWorkInsight(1L, "COMPANY", false);
        assertNotSame(first, next);
        assertEquals(InsightVO.SOURCE_AI, next.source());
        model.verify();
    }

    @Test
    @SuppressWarnings("unchecked")
    void previousProtocolCacheIsDiscarded() throws Exception {
        expectValid();
        expectValid();
        InsightVO first = service.getWorkInsight(1L, "COMPANY", false);
        Cache<String, Object> cache = (Cache<String, Object>) ReflectionTestUtils.getField(service, "cache");
        Object entry = cache.getIfPresent("1|COMPANY");
        String context = ReflectionTestUtils.invokeMethod(entry, "context");
        var constructor = entry.getClass().getDeclaredConstructor(int.class, String.class, InsightVO.class);
        constructor.setAccessible(true);
        cache.put("1|COMPANY", constructor.newInstance(1, context,
                new InsightVO("旧文案", "新增需求110条积压", "清理任务", InsightVO.SOURCE_AI, first.stats())));
        InsightVO result = service.getWorkInsight(1L, "COMPANY", false);
        assertFalse(result.para().contains("110"));
        assertNotSame(first, result);
        model.verify();
    }

    @Test
    void transientFailureStillRetriesAndRecovers() throws Exception {
        model.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectValid();
        assertEquals(InsightVO.SOURCE_AI, service.getWorkInsight(1L, "COMPANY", false).source());
        model.verify();
    }

    private static final class Fixture {
        int total = 115;
        int done = 92;
        int doing = 5;
        int delayed = 2;
        int overdue = 11;
        int highPending = 2;
        int todayNew = 1;
        int keyTasks = 4;
        int categoryTotal = 110;
        int categoryUnfinished = 21;

        Map<String, Object> aggregate() {
            return Map.ofEntries(Map.entry("total", total), Map.entry("done_cnt", done),
                    Map.entry("doing_cnt", doing), Map.entry("delayed_cnt", delayed), Map.entry("overdue", overdue),
                    Map.entry("due_today", 1), Map.entry("due_week", 3), Map.entry("high_pending", highPending),
                    Map.entry("key_tasks", keyTasks), Map.entry("done_last7", 10), Map.entry("done_prev7", 82),
                    Map.entry("new_last7", 6), Map.entry("today_new", todayNew), Map.entry("progress_sum", 9280));
        }
    }
}
