/**
 * 最新请求守卫：异步加载竞态防护。
 *
 * 范围切换等场景会连发两次加载；晚返回的旧请求若直接写状态会覆盖新范围数据。
 * 用法：每次加载先 `begin()` 取序号，`await` 之后用 `isCurrent(seq)` 判定——
 * 只有最新一次请求允许写入结果与收尾 loading，过期响应一律丢弃。
 */
export interface LatestGuard {
  /** 开始一次新请求，返回本次序号（单调递增） */
  begin(): number
  /** 该序号是否仍是最新一次（旧请求 await 回来后应为 false） */
  isCurrent(seq: number): boolean
}

export function createLatestGuard(): LatestGuard {
  let seq = 0
  return {
    begin: () => ++seq,
    isCurrent: (s: number) => s === seq,
  }
}
