package com.worktrace.common.api;

/**
 * 分页请求。page 从 1 起；size 只允许 20/50，其它值钳制到 20。
 */
public class PageReq {

    private int page = 1;
    private int size = 20;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int normalizedPage() {
        return Math.max(page, 1);
    }

    public int normalizedSize() {
        return size == 50 ? 50 : 20;
    }
}
