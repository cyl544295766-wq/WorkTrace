import axios, { AxiosError, type AxiosRequestConfig } from 'axios';

import type { ApiResponse } from './types';

export class ApiError extends Error {
  readonly code: number;
  readonly traceId: string;
  readonly status: number;
  readonly data: unknown;

  constructor(message: string, code: number, traceId: string, status = 0, data?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
    this.status = status;
    this.data = data;
  }
}

const TRACE_HEADER = 'X-Trace-Id';

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15_000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
});

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiResponse<unknown>>) => {
    const status = error.response?.status ?? 0;
    const body = error.response?.data;
    const traceId = body?.traceId || String(error.response?.headers?.[TRACE_HEADER.toLowerCase()] ?? '');

    return Promise.reject(
      new ApiError(body?.message || error.message || '网络请求失败', body?.code ?? -1, traceId, status, body?.data),
    );
  },
);

/** Calls an API and returns the full envelope so callers can surface traceId. */
export async function requestEnvelope<T>(config: AxiosRequestConfig): Promise<ApiResponse<T>> {
  const response = await http.request<ApiResponse<T>>(config);
  const body = response.data;

  if (body.code !== 0) {
    throw new ApiError(body.message, body.code, body.traceId, response.status, body.data);
  }

  return body;
}

/**
 * Calls an API and unwraps the unified envelope.
 * Non-zero business codes and HTTP errors become ApiError with traceId preserved.
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const body = await requestEnvelope<T>(config);
  return body.data;
}

