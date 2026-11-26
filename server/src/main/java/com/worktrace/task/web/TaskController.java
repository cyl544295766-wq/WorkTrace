package com.worktrace.task.web;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.worktrace.auth.dto.LoginUser;
import com.worktrace.common.api.Result;
import com.worktrace.task.dto.TaskCreateReq;
import com.worktrace.task.dto.TaskPageReq;
import com.worktrace.task.dto.TaskScopeMigrateVO;
import com.worktrace.task.dto.TaskScopeReq;
import com.worktrace.task.dto.TaskScrollPage;
import com.worktrace.task.dto.TaskScrollReq;
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
import java.time.LocalDateTime;

/**
 * 工作任务管理（REST）。
 *
 * <p>所有接口都从 <b>HttpSession</b> 取当前登录用户（{@code LOGIN_USER} → id = user_id），
 * 请求体里没有也不接受 user_id。登录态本身由 {@code LoginInterceptor} 拦截 /api/**（登录除外）保障，
 * 这里再判空一次兜底（未登录返回 401）。
 *
 * <ul>
 *   <li>POST   /api/tasks              —— 新增（范围缺省 COMPANY；子任务继承父范围）</li>
 *   <li>DELETE /api/tasks/{id}         —— 删除（级联后代）</li>
 *   <li>PUT    /api/tasks              —— 更新（部分更新，不改范围）</li>
 *   <li>PUT    /api/tasks/{id}/scope   —— 范围迁移（整棵子树，事务一致）</li>
 *   <li>GET    /api/tasks/page         —— 分页查询（范围 + status + 日期，页码钳 1-1000）</li>
 *   <li>GET    /api/tasks/scroll       —— 游标分页（keyset，无页码上限，前端拉全量用）</li>
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

    /** GET /api/tasks/page 分页查询（按范围、status、plan_date 日期范围筛选） */
    @GetMapping("/page")
    public Result<Page<BizTask>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String taskScope,
            HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        TaskPageReq query = new TaskPageReq(page, size, status, startDate, endDate, taskScope);
        return Result.ok(taskService.page(user.getId(), query));
    }

    /**
     * GET /api/tasks/scroll 游标分页（keyset：create_time DESC, id DESC 稳定排序，无页码上限）。
     * cursorTime/cursorId = 上一页最后一条的排序键（ISO LocalDateTime + id），首页两者都不传。
     * taskScope 缺省 COMPANY，与 /page 同一过滤口径。
     */
    @GetMapping("/scroll")
    public Result<TaskScrollPage> scroll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorTime,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "500") int size,
            @RequestParam(required = false) String taskScope,
            HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        return Result.ok(taskService.scroll(user.getId(),
                new TaskScrollReq(cursorTime, cursorId, size, taskScope)));
    }

    /**
     * PUT /api/tasks/{id}/scope 调整任务范围——顶层任务及其全部后代整体迁移（事务一致）。
     * 子任务不能单独跨范围；目标范围非法 400，任务不存在或非本人 404。
     */
    @PutMapping("/{id}/scope")
    public Result<TaskScopeMigrateVO> migrateScope(@PathVariable Long id,
                                                   @Valid @RequestBody TaskScopeReq req,
                                                   HttpSession session) {
        LoginUser user = currentUser(session);
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录或会话已过期");
        }
        int migrated = taskService.migrateScope(user.getId(), id, req.scope());
        return Result.ok(new TaskScopeMigrateVO(id, req.scope(), migrated));
    }
}
