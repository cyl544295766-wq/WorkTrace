package com.worktrace.common.api;

import java.util.List;

/**
 * 分页响应固定 {list,total,page,size}。
 */
public record PageResp<T>(
        List<T> list,
        long total,
        int page,
        int size
) {

    public static <T> PageResp<T> of(List<T> list, long total, int page, int size) {
        return new PageResp<>(list == null ? List.of() : list, total, page, size);
    }
}
