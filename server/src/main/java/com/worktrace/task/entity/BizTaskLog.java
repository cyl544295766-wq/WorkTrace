package com.worktrace.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务跟进记录（工作留痕时间线），对应表 biz_task_log。
 *
 * <ul>
 *   <li>log_type：VERIFY=找领导核实, NOTE=备注, DEV=开发, OTHER=其他</li>
 *   <li>所属任务删除时由 {@code TaskService#delete} 级联删除</li>
 * </ul>
 *
 * 每条记录归属一个 user_id（来自登录会话），读写均按 user_id 过滤。
 */
@Data
@TableName("biz_task_log")
public class BizTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 id */
    private Long taskId;

    /** 记录人（登录会话用户，绝不来自请求体） */
    private Long userId;

    /** 记录类型：VERIFY/NOTE/DEV/OTHER */
    private String logType;

    /** 跟进内容 */
    private String content;

    private LocalDateTime createTime;
}
