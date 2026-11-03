/** Unified response envelope from design.md §8.1. */
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
  traceId: string;
  ts: number;
}

/** Spring page payload used by list APIs (design.md §8.1: {list,total,page,size}). */
export interface PageResp<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export interface PageReq {
  page?: number;
  size?: number;
}
