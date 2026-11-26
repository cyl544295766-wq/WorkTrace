package com.worktrace.task;

import com.worktrace.common.api.Result;
import com.worktrace.common.exception.BizException;

/**
 * 任务范围常量与统一校验。
 *
 * <p>{@code COMPANY} = 公司任务（存量任务迁移后全部归入此类，也是查询与创建的缺省值，
 * 旧客户端不传范围时按公司任务处理）；{@code PERSONAL} = 个人任务。
 *
 * <p>范围只在当前登录用户内部区分（user_id 隔离不变），「公司任务」不是跨账号共享数据。
 * 非法值一律 400，绝不静默回退——否则会把个人任务误当公司任务读写。
 */
public final class TaskScope {

    /** 公司任务 */
    public static final String COMPANY = "COMPANY";

    /** 个人任务 */
    public static final String PERSONAL = "PERSONAL";

    /** 查询与创建的缺省范围（旧客户端缺省 → COMPANY） */
    public static final String DEFAULT = COMPANY;

    private TaskScope() {
    }

    /** 合法取值判定 */
    public static boolean isValid(String scope) {
        return COMPANY.equals(scope) || PERSONAL.equals(scope);
    }

    /**
     * 统一入口：null / 空白 → 缺省 {@link #DEFAULT}；非法值 → 400。
     * 用于查询参数（page / scroll / insights / reports）与请求体字段。
     */
    public static String normalize(String scope) {
        if (scope == null || scope.isBlank()) {
            return DEFAULT;
        }
        if (!isValid(scope)) {
            throw new BizException(Result.BAD_REQUEST, "任务范围只能是 COMPANY 或 PERSONAL");
        }
        return scope;
    }

    /** 展示名（前端同款文案，后端仅用于日志/提示） */
    public static String label(String scope) {
        return PERSONAL.equals(scope) ? "个人任务" : "公司任务";
    }
}
