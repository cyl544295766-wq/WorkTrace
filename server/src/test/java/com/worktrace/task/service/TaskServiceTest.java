package com.worktrace.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.worktrace.common.api.Result;
import com.worktrace.common.exception.BizException;
import com.worktrace.task.TaskScope;
import com.worktrace.task.dto.TaskCreateReq;
import com.worktrace.task.dto.TaskPageReq;
import com.worktrace.task.dto.TaskScrollPage;
import com.worktrace.task.dto.TaskScrollReq;
import com.worktrace.task.dto.TaskUpdateReq;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.entity.BizTaskLog;
import com.worktrace.task.mapper.BizTaskLogMapper;
import com.worktrace.task.mapper.BizTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务范围与分页行为测试：全部 SQL 在内存中模拟（mock Mapper），不连数据库。
 *
 * <p>覆盖：缺省范围 / 子任务继承 / 跨范围挂接拒绝 / 普通更新保留范围 /
 * page·scroll 范围过滤与游标分页（&gt;500 条 hasMore）/ 整树迁移（幂等 + 404）/ 跨用户隔离。
 */
class TaskServiceTest {

    private static final Long USER = 100L;
    private static final Long OTHER_USER = 200L;

    private BizTaskMapper mapper;
    private TaskService service;

    /** 最近一次被 mock 捕获的查询/更新 wrapper（断言 SQL 条件用） */
    private AbstractWrapper<?, ?, ?> lastWrapper;
    private BizTask selectOneResult;
    private final Deque<List<BizTask>> selectListResults = new ArrayDeque<>();
    private BizTask inserted;
    private BizTask updatedEntity;

    @BeforeAll
    static void initTable() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "task-test");
        TableInfoHelper.initTableInfo(assistant, BizTask.class);
        TableInfoHelper.initTableInfo(assistant, BizTaskLog.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(BizTaskMapper.class);
        lastWrapper = null;
        selectOneResult = null;
        selectListResults.clear();
        inserted = null;
        updatedEntity = null;

        when(mapper.selectOne(any())).thenAnswer(inv -> {
            lastWrapper = inv.getArgument(0);
            return selectOneResult;
        });
        when(mapper.selectList(any())).thenAnswer(inv -> {
            lastWrapper = inv.getArgument(0);
            return selectListResults.isEmpty() ? List.of() : selectListResults.poll();
        });
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> {
            lastWrapper = inv.getArgument(1);
            return inv.getArgument(0);
        });
        when(mapper.insert(any(BizTask.class))).thenAnswer(inv -> {
            inserted = inv.getArgument(0);
            inserted.setId(900L);
            return 1;
        });
        when(mapper.update(any(BizTask.class), any())).thenAnswer(inv -> {
            updatedEntity = inv.getArgument(0);
            lastWrapper = inv.getArgument(1);
            return 1;
        });
        service = new TaskService(mapper, mock(BizTaskLogMapper.class));
    }

    /* ── 新增：缺省范围 / 显式范围 / 子任务继承 / 跨范围拒绝 ── */

    @Test
    void createTopLevelDefaultsToCompany() {
        BizTask t = service.create(USER, create(null, null));
        assertEquals(TaskScope.COMPANY, t.getTaskScope(), "缺省范围必须是 COMPANY（旧客户端兼容）");
        assertEquals(TaskScope.COMPANY, inserted.getTaskScope());
    }

    @Test
    void createTopLevelAcceptsExplicitPersonal() {
        BizTask t = service.create(USER, create(null, TaskScope.PERSONAL));
        assertEquals(TaskScope.PERSONAL, t.getTaskScope());
    }

    @Test
    void createRejectsUnknownScope() {
        BizException e = assertThrows(BizException.class, () -> service.create(USER, create(null, "SHARED")));
        assertEquals(Result.BAD_REQUEST, e.getCode());
        verify(mapper, never()).insert(any(BizTask.class));
    }

    @Test
    void createChildInheritsParentScope() {
        selectOneResult = parent(TaskScope.PERSONAL);
        BizTask t = service.create(USER, create(1L, null));
        assertEquals(TaskScope.PERSONAL, t.getTaskScope(), "子任务必须继承父任务范围");
        assertEquals(1L, t.getParentId());
    }

    @Test
    void createRejectsCrossScopeAttach() {
        selectOneResult = parent(TaskScope.PERSONAL);
        BizException e = assertThrows(BizException.class,
                () -> service.create(USER, create(1L, TaskScope.COMPANY)));
        assertEquals(Result.BAD_REQUEST, e.getCode());
        assertTrue(e.getMessage().contains("跨范围"), "错误信息要说明禁止跨范围挂接：" + e.getMessage());
        verify(mapper, never()).insert(any(BizTask.class));
    }

    @Test
    void createRejectsForeignParent() {
        selectOneResult = null; // 跨用户/不存在 → 查不到
        BizException e = assertThrows(BizException.class, () -> service.create(USER, create(42L, null)));
        assertEquals(Result.BAD_REQUEST, e.getCode());
        verify(mapper, never()).insert(any(BizTask.class));
    }

    /* ── 更新：未传保留原范围；改范围必须走迁移接口 ── */

    @Test
    void updateWithoutScopeKeepsOriginalScope() {
        selectOneResult = task(7L, TaskScope.PERSONAL);
        BizTask updated = service.update(USER, new TaskUpdateReq(7L, "新标题", null, null, null,
                null, null, null, null, null, null));
        assertNotNull(updated);
        assertNull(updatedEntity.getTaskScope(), "普通更新不得写入 task_scope（未传 = 保留原值）");
    }

    @Test
    void updateRejectsScopeChange() {
        selectOneResult = task(7L, TaskScope.PERSONAL);
        BizException e = assertThrows(BizException.class, () -> service.update(USER,
                new TaskUpdateReq(7L, null, null, null, null, null, null, null, null, null,
                        TaskScope.COMPANY)));
        assertEquals(Result.BAD_REQUEST, e.getCode());
        assertTrue(e.getMessage().contains("迁移"), "改范围必须提示走整树迁移：" + e.getMessage());
        verify(mapper, never()).update(any(BizTask.class), any());
    }

    @Test
    void updateRejectsUnknownScopeValue() {
        selectOneResult = task(7L, TaskScope.COMPANY);
        BizException e = assertThrows(BizException.class, () -> service.update(USER,
                new TaskUpdateReq(7L, null, null, null, null, null, null, null, null, null, "SHARED")));
        assertEquals(Result.BAD_REQUEST, e.getCode());
    }

    /* ── 查询：范围过滤（缺省 COMPANY）+ 游标分页 ── */

    @Test
    void pageFiltersByScopeAndDefaultsToCompany() {
        // 不传范围 → COMPANY
        service.page(USER, new TaskPageReq(1, 10, null, null, null, null));
        assertTrue(lastWrapper.getSqlSegment().contains("task_scope"), "page 必须按范围过滤");
        assertTrue(params(lastWrapper).contains(TaskScope.COMPANY), "缺省范围必须是 COMPANY");
        // 显式 PERSONAL
        service.page(USER, new TaskPageReq(1, 10, null, null, null, TaskScope.PERSONAL));
        assertTrue(params(lastWrapper).contains(TaskScope.PERSONAL));
        // 非法范围 → 400（查询前抛出，不发请求）
        assertThrows(BizException.class, () -> service.page(USER,
                new TaskPageReq(1, 10, null, null, null, "SHARED")));
        // 跨用户隔离始终在
        assertTrue(lastWrapper.getSqlSegment().contains("user_id"));
    }

    @Test
    void scrollFiltersByScopeAndKeepsCursorOrder() {
        service.scroll(USER, new TaskScrollReq(null, null, 500, null));
        assertTrue(lastWrapper.getSqlSegment().contains("task_scope"), "scroll 必须按范围过滤");
        assertTrue(params(lastWrapper).contains(TaskScope.COMPANY));
        assertTrue(lastWrapper.getSqlSegment().contains("create_time"), "游标排序键保持 create_time DESC");
        assertThrows(BizException.class, () -> service.scroll(USER,
                new TaskScrollReq(null, null, 500, "SHARED")));
    }

    @Test
    void scrollPagesBeyond500WithHasMore() {
        List<BizTask> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(task(10_000L + i, TaskScope.COMPANY));
        }
        selectListResults.add(rows);
        TaskScrollPage page = service.scroll(USER, new TaskScrollReq(null, null, 500, TaskScope.COMPANY));
        assertTrue(page.hasMore(), "501 行应判定还有下一页");
        assertEquals(500, page.records().size(), "单页最多 500");
        assertTrue(lastWrapper.getSqlSegment().contains("LIMIT 501"), "取 size+1 判定 hasMore");
        assertEquals(rows.get(499).getId(), page.nextCursorId(), "游标推进到本页末条");
    }

    @Test
    void scrollRejectsHalfCursor() {
        assertThrows(BizException.class, () -> service.scroll(USER,
                new TaskScrollReq(LocalDateTime.now(), null, 50, TaskScope.COMPANY)));
    }

    /* ── 整树范围迁移 ── */

    @Test
    void migrateScopeRejectsChildTaskWithoutUpdatingAnyRows() {
        selectOneResult = task(11L, TaskScope.COMPANY);
        selectOneResult.setParentId(10L);

        BizException e = assertThrows(BizException.class,
                () -> service.migrateScope(USER, 11L, TaskScope.PERSONAL));

        assertEquals(Result.BAD_REQUEST, e.getCode());
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).update(any(BizTask.class), any());
    }

    @Test
    void migrateScopeRejectsChildEvenWhenAlreadyInTarget() {
        selectOneResult = task(11L, TaskScope.PERSONAL);
        selectOneResult.setParentId(10L);

        BizException e = assertThrows(BizException.class,
                () -> service.migrateScope(USER, 11L, TaskScope.PERSONAL));

        assertEquals(Result.BAD_REQUEST, e.getCode());
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).update(any(BizTask.class), any());
    }

    @Test
    void migrateScopeUpdatesWholeSubtreeOnce() {
        selectOneResult = task(10L, TaskScope.COMPANY);
        BizTask child = task(11L, TaskScope.COMPANY);
        child.setParentId(10L);
        BizTask grand = task(12L, TaskScope.COMPANY);
        grand.setParentId(11L);
        selectListResults.add(List.of(child));
        selectListResults.add(List.of(grand));
        selectListResults.add(List.of());
        when(mapper.update(any(BizTask.class), any())).thenAnswer(inv -> {
            updatedEntity = inv.getArgument(0);
            lastWrapper = inv.getArgument(1);
            return 3;
        });

        int migrated = service.migrateScope(USER, 10L, TaskScope.PERSONAL);
        assertEquals(3, migrated, "整棵树（顶层 + 全部后代）一起迁移");

        ArgumentCaptor<BizTask> captor = ArgumentCaptor.forClass(BizTask.class);
        verify(mapper).update(captor.capture(), any());
        assertEquals(TaskScope.PERSONAL, captor.getValue().getTaskScope());
        assertTrue(lastWrapper.getSqlSegment().contains("user_id"), "UPDATE 必须限 userId");
        assertTrue(lastWrapper.getSqlSegment().contains("IN ("), "UPDATE 按整树 id 集合");
    }

    @Test
    void migrateScopeIsNoopWhenAlreadyInTarget() {
        selectOneResult = task(10L, TaskScope.PERSONAL);
        assertEquals(0, service.migrateScope(USER, 10L, TaskScope.PERSONAL), "已在目标范围 → 幂等 no-op");
        verify(mapper, never()).update(any(BizTask.class), any());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void migrateScopeRejectsMissingOrForeignTask() {
        selectOneResult = null;
        BizException e = assertThrows(BizException.class,
                () -> service.migrateScope(OTHER_USER, 10L, TaskScope.PERSONAL));
        assertEquals(Result.NOT_FOUND, e.getCode(), "跨用户任务必须 404");
        verify(mapper, never()).update(any(BizTask.class), any());
    }

    @Test
    void migrateScopeRejectsUnknownTarget() {
        selectOneResult = task(10L, TaskScope.COMPANY);
        BizException e = assertThrows(BizException.class,
                () -> service.migrateScope(USER, 10L, "SHARED"));
        assertEquals(Result.BAD_REQUEST, e.getCode());
        verify(mapper, never()).update(any(BizTask.class), any());
    }

    @Test
    void migrateScopeSubtreeWalkIsScopedToOwner() {
        selectOneResult = task(10L, TaskScope.COMPANY);
        selectListResults.add(List.of());
        service.migrateScope(USER, 10L, TaskScope.PERSONAL);
        assertTrue(lastWrapper.getSqlSegment().contains("user_id"), "整树查询/更新都必须按 userId 过滤");
    }

    /* ── 夹具 ── */

    /** 物化 wrapper 后再取参数（MP 的 eq 参数是惰性 Supplier，getSqlSegment 才写入 params） */
    private static List<Object> params(AbstractWrapper<?, ?, ?> w) {
        w.getSqlSegment();
        return new ArrayList<>(w.getParamNameValuePairs().values());
    }

    private static BizTask task(Long id, String scope) {
        BizTask t = new BizTask();
        t.setId(id);
        t.setUserId(USER);
        t.setTitle("任务" + id);
        t.setStatus(0);
        t.setProgress(0);
        t.setPriority(2);
        t.setTaskScope(scope);
        t.setCreateTime(LocalDateTime.now());
        return t;
    }

    private static BizTask parent(String scope) {
        return task(1L, scope);
    }

    private static TaskCreateReq create(Long parentId, String scope) {
        return new TaskCreateReq("新任务", null, null, null, null, null, null, parentId, null, null, scope);
    }
}
