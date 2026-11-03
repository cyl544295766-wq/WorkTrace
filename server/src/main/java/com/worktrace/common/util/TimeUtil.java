package com.worktrace.common.util;

import java.time.LocalDateTime;

/**
 * 时间唯一入口的雏形（ArchUnit R5：now() 只允许出现在 util 包）。
 */
public final class TimeUtil {

    private TimeUtil() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
