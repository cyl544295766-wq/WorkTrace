package com.worktrace.task.web;

import com.worktrace.auth.dto.LoginUser;
import com.worktrace.common.api.Result;
import com.worktrace.task.dto.TaskLogAddReq;
import com.worktrace.task.entity.BizTaskLog;
import com.worktrace.task.service.TaskLogService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务跟进记录（工作留痕时间线，REST）。
 *
 * <p>会话/登录态处理与 {@link TaskController} 一致。
 *
 * <ul>
 *   <li>GET    /api/tasks/logs?taskId=  —— 某任务的记录列表（时间正序）</li>
 *   <li>POST   /api/tasks/logs          —— 新增记录（类型：VERIFY/NOTE/DEV/OTHER）</li>
 *   <li>DELETE /api/tasks/logs/{id}     —— 删除单条记录（仅本人）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tasks/logs")
public class TaskLogController {

    private final TaskLogService taskLogService;

    public TaskLogController(TaskLogService taskLogService) {
        this.taskLogService = taskLogService;
    }

    /** 从会话取当前登录用户（拦截器已保证，此处防御性判空） */
    private LoginUser currentUser(HttpSession session) {
        return (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
    }

    /** GET /api/tasks/logs?taskId= 某任务的跟进记录列表 */
    @GetMapping
    public Result<List<BizTaskLog>> list(@RequestParam Long taskId, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        return Result.ok(taskLogService.list(user.getId(), taskId));
    }

    /** POST /api/tasks/logs 新增跟进记录 */
    @PostMapping
    public Result<BizTaskLog> add(@Valid @RequestBody TaskLogAddReq req, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        return Result.ok(taskLogService.add(user.getId(), req));
    }

    /** DELETE /api/tasks/logs/{id} 删除单条跟进记录（仅本人） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        if (!taskLogService.delete(user.getId(), id)) {
            return Result.fail(404, "记录不存在或无权操作");
        }
        return Result.ok();
    }
}
