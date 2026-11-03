package com.worktrace.common.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * traceId 的生成、MDC 写入与读取。12 位 hex。
 */
public final class TraceIdUtil {

    public static final String MDC_KEY = "traceId";

    private TraceIdUtil() {
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void put(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
