package com.worktrace.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工作任务，对应表 biz_task。
 *
 * <ul>
 *   <li>status：0=待办, 1=进行中, 2=已完成, 3=已延期</li>
 *   <li>priority：1=低, 2=中, 3=高</li>
 * </ul>
 *
 * 每条任务归属一个 user_id（来自登录会话）。列表/改/删都按 user_id 过滤，
 * 保证多账号之间任务互不可见（不写死 userId）。
 * 驼峰↔下划线映射由 mybatis-plus `map-underscore-to-camel-case: true` 完成。
 */
@Data
@TableName("biz_task")
public class BizTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 id（登录会话用户，绝不来自请求体） */
    private Long userId;

    private String title;

    /** 任务描述（长文本） */
    private String content;

    /** 0待办 1进行中 2已完成 3已延期 */
    private Integer status;

    /** 1低 2中 3高 */
    private Integer priority;

    /** 计划日期 plan_date */
    private LocalDate planDate;

    /** 实际工时 actual_hours（支持小数） */
    private BigDecimal actualHours;

    /** 父任务 id parent_id；NULL=顶层大任务。删除父任务时 Service 级联删后代 */
    private Long parentId;

    /** 业务分类 category：FEATURE/BUG/DATA/JOINT/DOC/OPS/OTHER（与前端选项一一对应） */
    private String category;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
