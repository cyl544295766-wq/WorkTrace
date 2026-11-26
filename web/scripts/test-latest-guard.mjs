import { readFileSync } from 'node:fs'
import test from 'node:test'
import assert from 'node:assert/strict'
import ts from 'typescript'

// 直接运行生产使用的守卫实现：只移除 TypeScript 类型，不引入测试框架或网络依赖。
const source = readFileSync(new URL('../src/utils/latestRequest.ts', import.meta.url), 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 },
}).outputText
const { createLatestGuard } = await import(`data:text/javascript;base64,${Buffer.from(output).toString('base64')}`)

test('序号单调递增，旧序号立即过期、新序号保持最新', () => {
  const guard = createLatestGuard()
  const first = guard.begin()
  const second = guard.begin()
  assert.equal(guard.isCurrent(first), false, '旧请求的序号必须失效')
  assert.equal(guard.isCurrent(second), true, '最新请求的序号必须有效')
  const third = guard.begin()
  assert.equal(guard.isCurrent(second), false, '再次 begin 后上一次也过期')
  assert.equal(guard.isCurrent(third), true)
})

test('快速切换范围：晚返回的旧范围响应不得覆盖新范围数据', async () => {
  const guard = createLatestGuard()
  let applied = null
  const load = async (scope, delay) => {
    const seq = guard.begin()
    await new Promise((r) => setTimeout(r, delay))
    if (!guard.isCurrent(seq)) return // 过期响应丢弃（与 TaskList/Home 的写状态分支一致）
    applied = scope
  }
  // 旧请求更晚完成：若无守卫会把 COMPANY 覆盖到 PERSONAL 上
  await Promise.all([load('COMPANY', 40), load('PERSONAL', 5)])
  assert.equal(applied, 'PERSONAL', '最新范围的结果必须保留')
})

test('过期请求的失败也不得污染新范围状态', async () => {
  const guard = createLatestGuard()
  let failureApplied = false
  let latestOk = false
  const failing = async () => {
    const seq = guard.begin()
    await new Promise((r) => setTimeout(r, 40))
    if (!guard.isCurrent(seq)) return
    failureApplied = true
  }
  const ok = async () => {
    const seq = guard.begin()
    await new Promise((r) => setTimeout(r, 5))
    if (!guard.isCurrent(seq)) return
    latestOk = true
  }
  await Promise.all([failing(), ok()])
  assert.equal(latestOk, true, '最新请求正常写入')
  assert.equal(failureApplied, false, '旧请求的失败分支不得执行')
})

test('连续多次 begin 只认最后一次', () => {
  const guard = createLatestGuard()
  const seqs = [guard.begin(), guard.begin(), guard.begin(), guard.begin()]
  for (let i = 0; i < seqs.length - 1; i++) {
    assert.equal(guard.isCurrent(seqs[i]), false, `第 ${i + 1} 次必须过期`)
  }
  assert.equal(guard.isCurrent(seqs[seqs.length - 1]), true)
})
