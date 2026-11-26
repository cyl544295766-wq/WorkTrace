package com.worktrace.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务业务逻辑。
 *
 * <p>核心约束：所有读写都以 <b>user_id（登录会话用户）</b> 为过滤条件 —— 增删改查均带
 * {@code .eq(BizTask::getUserId, userId)}，多账号数据互不可见，且不写死任何 userId。
 *
 * <p>任务范围（task_scope）：查询按范围过滤；子任务创建时强制继承父任务范围；
 * 普通更新不改范围（传入不同值 400）；范围变更只能整棵子树迁移（事务保证一致）。
 * 范围只是当前用户内部的分组，user_id 隔离不变。
 */
@Service
public class TaskService {

    /** 状态/优先级默认值 */
    private static final int DEFAULT_STATUS = 0;   // 待办
    private static final int DEFAULT_PRIORITY = 2; // 中

    private final BizTaskMapper bizTaskMapper;
    private final BizTaskLogMapper bizTaskLogMapper;

    public TaskService(BizTaskMapper bizTaskMapper, BizTaskLogMapper bizTaskLogMapper) {
        this.bizTaskMapper = bizTaskMapper;
        this.bizTaskLogMapper = bizTaskLogMapper;
    }

    /** 默认分类（前端未传时） */
    private static final String DEFAULT_CATEGORY = "OTHER";

    /** 新增任务，归属会话用户；parentId 非空时校验父任务归属与范围，非法则 400 */
    public BizTask create(Long userId, TaskCreateReq req) {
        BizTask task = new BizTask();
        task.setUserId(userId);
        task.setTitle(req.title());
        task.setContent(req.content());
        task.setStatus(req.status() == null ? DEFAULT_STATUS : req.status());
        task.setPriority(req.priority() == null ? DEFAULT_PRIORITY : req.priority());
        // 进度默认 0；直接建成已完成 → 100（与 update 的状态联动规则一致）
        task.setProgress(req.status() != null && req.status() == 2
                ? 100 : (req.progress() == null ? 0 : req.progress()));
        task.setPlanDate(req.planDate());
        task.setActualHours(req.actualHours());
        task.setCategory(req.category() == null || req.category().isBlank()
                ? DEFAULT_CATEGORY : req.category());
        task.setIsKey(req.isKey() == null ? 0 : req.isKey()); // 重点标记，默认普通

        // 层级：父任务必须存在且属于当前用户（防把子任务挂到别人名下）；
        // 子任务强制继承父任务范围——显式传入不一致的范围一律 400，禁止跨范围挂接。
        if (req.parentId() != null) {
            BizTask parent = bizTaskMapper.selectOne(
                    wrapsOwned(userId).eq(BizTask::getId, req.parentId()));
            if (parent == null) {
                throw new BizException(Result.BAD_REQUEST, "父任务不存在或无权操作");
            }
            String parentScope = normalizeScope(parent.getTaskScope());
            if (req.taskScope() != null && !req.taskScope().isBlank()
                    && !parentScope.equals(req.taskScope())) {
                throw new BizException(Result.BAD_REQUEST,
                        "子任务范围必须与父任务一致，不允许跨范围挂接父子任务");
            }
            task.setParentId(req.parentId());
            task.setTaskScope(parentScope); // 继承父任务范围
        } else {
            // 顶层任务：用请求范围，缺省 COMPANY（旧客户端不传也按公司任务处理）
            task.setTaskScope(TaskScope.normalize(req.taskScope()));
        }

        LocalDateTime now = LocalDateTime.now();
        task.setCreateTime(now);
        task.setUpdateTime(now);

        bizTaskMapper.insert(task);
        return task;
    }

    /** 历史行 task_scope 可能为 null（迁移前数据），统一按 COMPANY 解释 */
    private static String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? TaskScope.DEFAULT : scope;
    }

    /**
     * 删除任务（仅本人）——<b>级联删除全部后代</b>（前端提示"及其子任务"）。
     * 按 parent_id 逐层收集后代 id，最后一并 DELETE；userId 过滤保证只删自己的。
     *
     * @return true 删除成功；false 任务不存在或非本人
     */
    public boolean delete(Long userId, Long id) {
        BizTask target = bizTaskMapper.selectOne(wrapsOwned(userId).eq(BizTask::getId, id));
        if (target == null) {
            return false;
        }
        // BFS 收集后代（Set 防脏数据成环导致死循环）
        Set<Long> ids = new HashSet<>();
        ids.add(id);
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(id);
        while (!frontier.isEmpty()) {
            List<BizTask> children = bizTaskMapper.selectList(
                    wrapsOwned(userId).in(BizTask::getParentId, frontier));
            frontier.clear(); // 逐层推进：下一轮只查本轮新发现的孩子，否则队列只增不减会死循环
            for (BizTask child : children) {
                if (ids.add(child.getId())) {
                    frontier.add(child.getId());
                }
            }
        }
        boolean removed = bizTaskMapper.delete(Wrappers.<BizTask>lambdaQuery()
                .eq(BizTask::getUserId, userId)
                .in(BizTask::getId, ids)) > 0;
        if (removed) {
            // 级联删除任务的跟进记录（ids 已由 user_id 圈定，无需再过滤）
            bizTaskLogMapper.delete(Wrappers.<BizTaskLog>lambdaQuery()
                    .in(BizTaskLog::getTaskId, ids));
        }
        return removed;
    }

    /** 按归属查单个任务（供跟进记录等模块校验"任务存在且属于当前用户"） */
    public BizTask getOwned(Long userId, Long id) {
        return bizTaskMapper.selectOne(wrapsOwned(userId).eq(BizTask::getId, id));
    }

    /**
     * 部分更新（仅本人）。DTO 为空的字段不写入（MP 按 NOT_NULL 生成 SET）。
     *
     * <p>完成时间 finish_time 为派生字段：状态首次变为已完成(2)时打当前时间戳
     * （重复保存已完成不刷新）；移出已完成时清空。清空 null 须走 UpdateWrapper
     * 的 {@code set}（实体 null 字段会被 MP 的 NOT_NULL 策略忽略）。
     *
     * @return 更新后的任务；null = 任务不存在或非本人
     */
    public BizTask update(Long userId, TaskUpdateReq req) {
        BizTask old = bizTaskMapper.selectOne(wrapsOwned(userId).eq(BizTask::getId, req.id()));
        if (old == null) {
            return null;
        }

        // 普通更新不改范围：未传 taskScope 保留原值（upd 不设该字段，MP NOT_NULL 策略不写入）；
        // 传入与原值不同的范围直接 400 —— 范围变更必须整棵子树迁移，防止个人任务被改回公司任务
        if (req.taskScope() != null && !req.taskScope().equals(normalizeScope(old.getTaskScope()))) {
            throw new BizException(Result.BAD_REQUEST,
                    "任务范围变更需整棵子树一起迁移，请使用范围迁移接口");
        }

        BizTask upd = new BizTask();
        upd.setTitle(req.title());
        upd.setContent(req.content());
        upd.setStatus(req.status());
        upd.setPriority(req.priority());
        upd.setProgress(req.progress());
        upd.setPlanDate(req.planDate());
        upd.setActualHours(req.actualHours());
        if (req.category() != null && !req.category().isBlank()) {
            upd.setCategory(req.category());
        }
        upd.setIsKey(req.isKey()); // 重点标记：null=不修改（NOT_NULL 策略），0/1 都写入
        upd.setUpdateTime(LocalDateTime.now());

        // 完成时间派生：以请求后的最终状态为准；状态→已完成时进度强制 100（联动）
        int newStatus = req.status() != null ? req.status() : old.getStatus();
        if (newStatus == 2) {
            upd.setProgress(100); // 勾完成 → 进度拉满
        }
        LambdaUpdateWrapper<BizTask> wrapper = Wrappers.<BizTask>lambdaUpdate()
                .eq(BizTask::getUserId, userId)
                .eq(BizTask::getId, req.id());
        if (newStatus == 2 && old.getStatus() != 2) {
            upd.setFinishTime(LocalDateTime.now()); // 待办/进行中 → 已完成：打戳
        } else if (newStatus != 2 && old.getFinishTime() != null) {
            wrapper.set(BizTask::getFinishTime, null); // 移出已完成：清空
        }

        int affected = bizTaskMapper.update(upd, wrapper);
        if (affected == 0) {
            return null;
        }
        return bizTaskMapper.selectOne(wrapsOwned(userId).eq(BizTask::getId, req.id()));
    }

    /** 分页参数上限：页大小 500（与前端拉全量循环口径一致），页码 1-1000（防超大 OFFSET 扫描） */
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_PAGE_NUMBER = 1000;

    /**
     * 分页查询（仅本人 + 范围 + 状态/日期范围筛选）。
     * 非法页码/页大小一律钳制处理（不报错）：page 钳到 1-1000，size 钳到 1-500（≤0 回退默认 10），
     * 非数字参数由 Spring 类型转换统一 400；范围缺省 COMPANY、非法值 400。
     * 排序 create_time DESC, id DESC：id 决序保证同秒创建的任务跨页顺序稳定（offset 分页不重不漏的前提）。
     */
    public Page<BizTask> page(Long userId, TaskPageReq req) {
        int current = Math.min(Math.max(req.page(), 1), MAX_PAGE_NUMBER);
        int size = req.size() <= 0 ? 10 : Math.min(req.size(), MAX_PAGE_SIZE);
        Page<BizTask> page = new Page<>(current, size);

        LambdaQueryWrapper<BizTask> wrapper = wrapsOwned(userId)
                .eq(BizTask::getTaskScope, TaskScope.normalize(req.taskScope()))
                .eq(req.status() != null, BizTask::getStatus, req.status())
                .ge(req.startDate() != null, BizTask::getPlanDate, req.startDate())
                .le(req.endDate() != null, BizTask::getPlanDate, req.endDate())
                .orderByDesc(BizTask::getCreateTime)
                .orderByDesc(BizTask::getId);

        return bizTaskMapper.selectPage(page, wrapper);
    }

    /**
     * 游标分页（keyset）：排序固定 (create_time DESC, id DESC)，游标 = 上一页最后一条的
     * (create_time, id)，取 size+1 条判定 hasMore（末页不多发一次空请求）。
     *
     * <p>对比 offset 分页（/page，页码钳 1-1000）：keyset 没有页码上限——前端拉全量走这里，
     * 任务再多也不会因页码钳制被截断；且页间数据增删不会像 offset 那样造成窗口漂移
     * （删除只让行数变少，新增的任务排序键比游标新、不会重复交付）。
     * cursorTime 与 cursorId 必须同时出现或同时为空，半缺即 400（防止隐式从头翻页掩盖游标 bug）。
     */
    public TaskScrollPage scroll(Long userId, TaskScrollReq req) {
        int size = req.size() <= 0 ? MAX_PAGE_SIZE : Math.min(req.size(), MAX_PAGE_SIZE);
        boolean hasCursor = req.cursorTime() != null || req.cursorId() != null;
        if (hasCursor && (req.cursorTime() == null || req.cursorId() == null)) {
            throw new BizException(400, "分页游标参数不完整：cursorTime 与 cursorId 必须同时提供");
        }

        LambdaQueryWrapper<BizTask> wrapper = wrapsOwned(userId)
                .eq(BizTask::getTaskScope, TaskScope.normalize(req.taskScope()));
        if (hasCursor) {
            LocalDateTime t = req.cursorTime();
            Long cid = req.cursorId();
            // (create_time < 游标) OR (create_time = 游标 AND id < 游标id)——严格小于游标键，不重复交付
            wrapper.and(w -> w
                    .lt(BizTask::getCreateTime, t)
                    .or(n -> n.eq(BizTask::getCreateTime, t).lt(BizTask::getId, cid)));
        }
        wrapper.orderByDesc(BizTask::getCreateTime)
                .orderByDesc(BizTask::getId)
                .last("LIMIT " + (size + 1));
        List<BizTask> rows = bizTaskMapper.selectList(wrapper);

        boolean more = rows.size() > size;
        if (more) {
            rows = rows.subList(0, size);
        }
        BizTask last = more ? rows.get(rows.size() - 1) : null;
        return new TaskScrollPage(
                rows,
                more,
                last == null ? null : last.getCreateTime(),
                last == null ? null : last.getId());
    }

    /**
     * 整棵子树范围迁移：顶层任务及其全部后代一起改范围，子任务不能单独跨范围。
     *
     * <p>事务保证成功或失败一致（BFS 读 + 单条 UPDATE 在同一事务内；UPDATE 单语句本身原子）。
     * userId 过滤贯穿 BFS 与 UPDATE，跨用户 id 查不到也不会被改。
     *
     * @param id          被迁移的顶层任务 id；直接传入子任务 id 返回 400
     * @param targetScope 目标范围（已校验合法）
     * @return 实际更新行数；已是目标范围返回 0（幂等）
     * @throws BizException(404) 任务不存在或非本人
     */
    @Transactional
    public int migrateScope(Long userId, Long id, String targetScope) {
        // 目标范围校验放在最前：非法值在任何查询/写入之前 400（Controller @Pattern 之外的纵深防御）
        String scope = TaskScope.normalize(targetScope);
        BizTask target = bizTaskMapper.selectOne(wrapsOwned(userId).eq(BizTask::getId, id));
        if (target == null) {
            throw new BizException(Result.NOT_FOUND, "任务不存在或无权操作");
        }
        if (target.getParentId() != null) {
            throw new BizException(Result.BAD_REQUEST,
                    "子任务不能单独调整范围，请迁移所属顶层任务及其全部子任务");
        }
        if (scope.equals(normalizeScope(target.getTaskScope()))) {
            return 0; // 已在目标范围：幂等 no-op
        }

        // BFS 收集整棵子树（与 delete 同一套逐层推进 + Set 防脏数据成环）
        Set<Long> ids = new HashSet<>();
        ids.add(id);
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(id);
        while (!frontier.isEmpty()) {
            List<BizTask> children = bizTaskMapper.selectList(
                    wrapsOwned(userId).in(BizTask::getParentId, frontier));
            frontier.clear();
            for (BizTask child : children) {
                if (ids.add(child.getId())) {
                    frontier.add(child.getId());
                }
            }
        }

        BizTask upd = new BizTask();
        upd.setTaskScope(scope);
        upd.setUpdateTime(LocalDateTime.now());
        return bizTaskMapper.update(upd, Wrappers.<BizTask>lambdaUpdate()
                .eq(BizTask::getUserId, userId)
                .in(BizTask::getId, ids));
    }

    /** 归属过滤条件（user_id = 当前会话用户） */
    private LambdaQueryWrapper<BizTask> wrapsOwned(Long userId) {
        return Wrappers.<BizTask>lambdaQuery().eq(BizTask::getUserId, userId);
    }
}
