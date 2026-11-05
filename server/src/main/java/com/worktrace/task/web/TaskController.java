package com.worktrace.task.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.worktrace.auth.dto.LoginUser;
import com.worktrace.common.api.Result;
import com.worktrace.task.dto.TaskCreateReq;
import com.worktrace.task.dto.TaskPageReq;
import com.worktrace.task.dto.TaskUpdateReq;
import com.worktrace.task.entity.BizTask;
import com.worktrace.task.service.TaskService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 工作任务管理（REST）。
 *
 * <p>所有接口都从 <b>HttpSession</b> 取当前登录用户（{@code LOGIN_USER} → id = user_id），
 * 请求体里没有也不接受 user_id。登录态本身由 {@code LoginInterceptor} 拦截 /api/**（登录除外）保障，
 * 这里再判空一次兜底（未登录返回 401）。
 *
 * <ul>
 *   <li>POST   /api/tasks        —— 新增</li>
 *   <li>DELETE /api/tasks/{id}   —— 删除</li>
 *   <li>PUT    /api/tasks        —— 更新（部分更新）</li>
 *   <li>GET    /api/tasks/page   —— 分页查询（status + 日期范围）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 从会话取当前登录用户（拦截器已保证，此处防御性判空） */
    private LoginUser currentUser(HttpSession session) {
        return (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
    }

    /** POST /api/tasks 新增任务 */
    @PostMapping
    public Result<BizTask> create(@Valid @RequestBody TaskCreateReq req, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        return Result.ok(taskService.create(user.getId(), req));
    }

    /** DELETE /api/tasks/{id} 删除任务（仅本人） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        if (!taskService.delete(user.getId(), id)) {
            return Result.fail(404, "任务不存在或无权操作");
        }
        return Result.ok();
    }

    /** PUT /api/tasks 更新任务（部分更新，仅本人） */
    @PutMapping
    public Result<BizTask> update(@Valid @RequestBody TaskUpdateReq req, HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        BizTask updated = taskService.update(user.getId(), req);
        if (updated == null) {
            return Result.fail(404, "任务不存在或无权操作");
        }
        return Result.ok(updated);
    }

    /** GET /api/tasks/page 分页查询（按 status、plan_date 日期范围筛选） */
    @GetMapping("/page")
    public Result<Page<BizTask>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        TaskPageReq query = new TaskPageReq(page, size, status, startDate, endDate);
        return Result.ok(taskService.page(user.getId(), query));
    }
}
