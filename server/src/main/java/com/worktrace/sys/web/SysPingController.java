package com.worktrace.sys.web;

import com.worktrace.common.api.Result;
import com.worktrace.common.util.TimeUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨架冒烟端点：用于验证统一响应体与 LocalDateTime 序列化。
 * 不含任何业务数据，后续可保留为运维探针。
 */
@RestController
@RequestMapping("/api/sys")
public class SysPingController {

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("serverTime", TimeUtil.now());
        body.put("timezone", ZoneId.systemDefault().getId());
        return Result.ok(body);
    }
}
