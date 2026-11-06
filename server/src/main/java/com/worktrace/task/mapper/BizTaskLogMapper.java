package com.worktrace.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.worktrace.task.entity.BizTaskLog;
import org.apache.ibatis.annotations.Mapper;

/** 任务跟进记录表访问 */
@Mapper
public interface BizTaskLogMapper extends BaseMapper<BizTaskLog> {
}
