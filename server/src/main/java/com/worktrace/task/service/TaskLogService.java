package com.worktrace.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.worktrace.common.api.Result;
import com.worktrace.common.exception.BizException;
import com.worktrace.task.dto.TaskLogAddReq;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.entity.BizTaskLog;
import com.worktrace.task.mapper.BizTaskLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 任务跟进记录业务逻辑（工作留痕时间线）。
 *
 * <p>与 {@link TaskService} 同样的约束：所有读写都以 <b>user_id（登录会话用户）</b>
 * 为过滤条件；写入前校验所属任务存在且属于当前用户。
 */
@Service
public class TaskLogService {

    /** 允许的记录类型（与前端选项一一对应） */
    private static final Set<String> ALLOWED_TYPES = Set.of("VERIFY", "NOTE", "DEV", "OTHER");

    private final BizTaskLogMapper logMapper;
    private final TaskService taskService;

    public TaskLogService(BizTaskLogMapper logMapper, TaskService taskService) {
        this.logMapper = logMapper;
        this.taskService = taskService;
    }

    /** 某任务的全部跟进记录，按时间正序（时间线自上而下阅读） */
    public List<BizTaskLog> list(Long userId, Long taskId) {
        assertTaskOwned(userId, taskId);
        return logMapper.selectList(Wrappers.<BizTaskLog>lambdaQuery()
                .eq(BizTaskLog::getUserId, userId)
                .eq(BizTaskLog::getTaskId, taskId)
                .orderByAsc(BizTaskLog::getCreateTime)
                .orderByAsc(BizTaskLog::getId));
    }

    /** 新增跟进记录；logType 非法或任务非本人 → 400 */
    public BizTaskLog add(Long userId, TaskLogAddReq req) {
        if (!ALLOWED_TYPES.contains(req.logType())) {
            throw new BizException(Result.BAD_REQUEST, "无效的记录类型");
        }
        assertTaskOwned(userId, req.taskId());

        BizTaskLog log = new BizTaskLog();
        log.setTaskId(req.taskId());
        log.setUserId(userId);
        log.setLogType(req.logType());
        log.setContent(req.content().trim());
        log.setCreateTime(LocalDateTime.now());
        logMapper.insert(log);
        return log;
    }

    /** 删除单条记录（仅本人）；不存在返回 false */
    public boolean delete(Long userId, Long id) {
        return logMapper.delete(Wrappers.<BizTaskLog>lambdaQuery()
                .eq(BizTaskLog::getUserId, userId)
                .eq(BizTaskLog::getId, id)) > 0;
    }

    /** 校验任务存在且属于当前用户（防跨用户读写他人任务的记录） */
    private void assertTaskOwned(Long userId, Long taskId) {
        BizTask task = taskService.getOwned(userId, taskId);
        if (task == null) {
            throw new BizException(Result.BAD_REQUEST, "任务不存在或无权操作");
        }
    }
}
