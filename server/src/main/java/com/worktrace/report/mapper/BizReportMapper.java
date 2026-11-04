package com.worktrace.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.worktrace.report.entity.BizReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * biz_report 表 CRUD。
 * {@code @Mapper} 由 MyBatis-Plus 自动扫描注册；实体无 deleted 字段 → 物理删除。
 */
@Mapper
public interface BizReportMapper extends BaseMapper<BizReport> {
}
