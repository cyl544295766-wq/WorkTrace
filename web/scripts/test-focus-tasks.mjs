import { readFileSync } from 'node:fs'
import test from 'node:test'
import assert from 'node:assert/strict'
import ts from 'typescript'

// 直接运行生产使用的纯视图函数；只移除 TypeScript 类型，不引入测试框架或网络依赖。
const source = readFileSync(new URL('../src/utils/focusTasks.ts', import.meta.url), 'utf8')
const output = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 },
}).outputText
const { buildFocusTasks, taskDeadline } = await import(`data:text/javascript;base64,${Buffer.from(output).toString('base64')}`)
const today = '2026-11-26'
const row = (id, planDate = null, extra = {}) => ({ id, planDate, isKey: 1, status: 0, priority: 2, ...extra })
const ids = summary => summary.items.map(task => task.id)

test('only unfinished marked tasks are shown; total includes completed marked tasks', () => {
  const result = buildFocusTasks([row(1), row(2, null, { status: 2 }), row(3, null, { isKey: 0 }), row(4, null, { status: 3 })], today)
  assert.deepEqual(ids(result), [1, 4])
  assert.equal(result.total, 3)
  assert.equal(result.unfinished, 2)
  assert.equal(result.remaining, 0)
})

test('overdue precedes today, future and unscheduled; only three are visible', () => {
  const result = buildFocusTasks([row(1), row(2, '2026-12-01'), row(3, today), row(4, '2026-11-25')], today)
  assert.deepEqual(ids(result), [4, 3, 2])
  assert.equal(result.unfinished, 4)
  assert.equal(result.remaining, 1)
})

test('planned date precedes priority; equal dates use descending priority then ascending ID', () => {
  const result = buildFocusTasks([
    row(7, '2026-11-25', { priority: 3 }), row(5, '2026-11-25', { priority: 3 }),
    row(2, '2026-11-25', { priority: 1 }), row(9, '2026-11-20', { priority: 1 }),
  ], today)
  assert.deepEqual(ids(result), [9, 5, 7])
})

test('unscheduled tasks use the same priority and ID tie breakers', () => {
  assert.deepEqual(ids(buildFocusTasks([row(4), row(8, null, { priority: 3 }), row(6, null, { priority: 3 })], today)), [6, 8, 4])
})

test('deadline labels distinguish overdue, today, tomorrow, future and no date', () => {
  assert.deepEqual(taskDeadline('2026-11-24', today), { label: '已逾期 2 天', tone: 'overdue', daysUntil: -2 })
  assert.equal(taskDeadline(today, today).label, '今日到期')
  assert.equal(taskDeadline('2026-11-27', today).label, '明日到期')
  assert.equal(taskDeadline('2026-12-01', today).label, '12-01')
  assert.equal(taskDeadline('2027-01-01', today).label, '2027-01-01')
  assert.equal(taskDeadline(null, today).label, '未设置日期')
})

test('calendar calculations handle leap days, year boundaries and daylight-saving boundaries', () => {
  assert.equal(taskDeadline('2024-02-29', '2024-03-01').label, '已逾期 1 天')
  assert.equal(taskDeadline('2027-01-01', '2026-12-31').label, '明日到期')
  assert.equal(taskDeadline('2026-03-07', '2026-03-09').label, '已逾期 2 天')
})

test('invalid or missing planned dates never produce NaN or a false overdue label', () => {
  for (const value of [null, '', '2026-02-30', 'not-a-date', '2026-1-1']) {
    assert.equal(taskDeadline(value, today).tone, 'unscheduled')
  }
})

test('completion removes the task and fills the third position without cancelling its key flag', () => {
  const tasks = [row(1, '2026-11-24'), row(2, '2026-11-25'), row(3, today), row(4, '2026-11-27')]
  assert.deepEqual(ids(buildFocusTasks(tasks, today)), [1, 2, 3])
  tasks[0].status = 2 // 模拟服务端确认后的回读
  const result = buildFocusTasks(tasks, today)
  assert.deepEqual(ids(result), [2, 3, 4])
  assert.equal(result.total, 4)
  assert.equal(result.unfinished, 3)
  assert.equal(tasks[0].isKey, 1)
})

test('all completed and no marked tasks have distinguishable counts', () => {
  const allDone = buildFocusTasks([row(1, null, { status: 2 }), row(2, null, { status: 2 })], today)
  assert.deepEqual(ids(allDone), [])
  assert.equal(allDone.total, 2)
  assert.equal(allDone.unfinished, 0)
  assert.equal(buildFocusTasks([], today).total, 0)
})

test('view generation does not mutate the input, state or key flags', () => {
  const tasks = [row(4, '2026-11-25'), row(1, today, { status: 3 }), row(9)]
  const before = structuredClone(tasks)
  buildFocusTasks(tasks, today)
  assert.deepEqual(tasks, before)
  assert.equal(Object.hasOwn(tasks[0], 'deadline'), false)
})

test('the next calendar day updates urgency without changing business status', () => {
  const task = row(1, today)
  assert.equal(buildFocusTasks([task], today).items[0].deadline.tone, 'today')
  assert.equal(buildFocusTasks([task], '2026-11-27').items[0].deadline.label, '已逾期 1 天')
  assert.equal(task.status, 0)
})
