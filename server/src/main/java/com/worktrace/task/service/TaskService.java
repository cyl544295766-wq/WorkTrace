package com.worktrace.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.worktrace.task.dto.TaskCreateReq;
import com.worktrace.task.dto.TaskPageReq;
import com.worktrace.task.dto.TaskUpdateReq;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.mapper.BizTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 任务业务逻辑。
 *
 * <p>核心约束：所有读写都以 <b>user_id（登录会话用户）</b> 为过滤条件 —— 增删改查均带
 * {@code .eq(BizTask::getUserId, userId)}，多账号数据互不可见，且不写死任何 userId。
 */
@Service
public class TaskService {

    /** 状态/优先级默认值 */
    private static final int DEFAULT_STATUS = 0;   // 待办
    private static final int DEFAULT_PRIORITY = 2; // 中

    private final BizTaskMapper bizTaskMapper;

    public TaskService(BizTaskMapper bizTaskMapper) {
        this.bizTaskMapper = bizTaskMapper;
    }

    /** 新增任务，归属会话用户 */
    public BizTask create(Long userId, TaskCreateReq req) {
        BizTask task = new BizTask();
        task.setUserId(userId);
        task.setTitle(req.title());
        task.setContent(req.content());
        task.setStatus(req.status() == null ? DEFAULT_STATUS : req.status());
        task.setPriority(req.priority() == null ? DEFAULT_PRIORITY : req.priority());
        task.setPlanDate(req.planDate());
        task.setActualHours(req.actualHours());

        LocalDateTime now = LocalDateTime.now();
        task.setCreateTime(now);
        task.setUpdateTime(now);

        bizTaskMapper.insert(task);
        return task;
    }

    /**
     * 删除任务（仅本人）。
     *
     * @return true 删除成功；false 任务不存在或非本人
     */
    public boolean delete(Long userId, Long id) {
        return bizTaskMapper.delete(wrapsOwned(userId).eq(BizTask::getId, id)) > 0;
    }

    /**
     * 部分更新（仅本人）。DTO 为空的字段不写入（MP 按 NOT_NULL 生成 SET）。
     *
     * @return 更新后的任务；null = 任务不存在或非本人
     */
    public BizTask update(Long userId, TaskUpdateReq req) {
        BizTask upd = new BizTask();
        upd.setTitle(req.title());
        upd.setContent(req.content());
        upd.setStatus(req.status());
        upd.setPriority(req.priority());
        upd.setPlanDate(req.planDate());
        upd.setActualHours(req.actualHours());
        upd.setUpdateTime(LocalDateTime.now());

        int affected = bizTaskMapper.update(upd, wrapsOwned(userId).eq(BizTask::getId, req.id()));
        if (affected == 0) {
            return null;
        }
        return bizTaskMapper.selectOne(wrapsOwned(userId).eq(BizTask::getId, req.id()));
    }

    /** 分页查询（仅本人 + 状态/日期范围筛选） */
    public Page<BizTask> page(Long userId, TaskPageReq req) {
        int current = Math.max(req.page(), 1);
        int size = req.size() <= 0 ? 10 : req.size();
        Page<BizTask> page = new Page<>(current, size);

        LambdaQueryWrapper<BizTask> wrapper = wrapsOwned(userId)
                .eq(req.status() != null, BizTask::getStatus, req.status())
                .ge(req.startDate() != null, BizTask::getPlanDate, req.startDate())
                .le(req.endDate() != null, BizTask::getPlanDate, req.endDate())
                .orderByDesc(BizTask::getCreateTime);

        return bizTaskMapper.selectPage(page, wrapper);
    }

    /** 归属过滤条件（user_id = 当前会话用户） */
    private LambdaQueryWrapper<BizTask> wrapsOwned(Long userId) {
        return Wrappers.<BizTask>lambdaQuery().eq(BizTask::getUserId, userId);
    }
}
