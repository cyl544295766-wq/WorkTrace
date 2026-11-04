package com.worktrace.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.worktrace.task.entity.BizTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * biz_task 表 CRUD。
 * {@code @Mapper} 由 MyBatis-Plus 的 AutoConfiguredMapperScannerRegistrar 自动扫描注册（无需 @MapperScan）。
 * 实体无 deleted 字段 → 删除为物理 DELETE（不受全局 logic-delete 影响）。
 */
@Mapper
public interface BizTaskMapper extends BaseMapper<BizTask> {
}
