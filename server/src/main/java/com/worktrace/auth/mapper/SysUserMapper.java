package com.worktrace.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.worktrace.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/** sys_user 表 CRUD；@Mapper 由 MyBatis 自动扫描注册（无需 @MapperScan）。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
