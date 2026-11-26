import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'
import assert from 'node:assert/strict'
import ts from 'typescript'

// 运行组件中的真实异步函数，网络与表格仅作为依赖注入。
// 这样会覆盖“守卫存在，但创建回调没有使用”的接线缺陷。
const source = readFileSync(new URL('../src/views/TaskList.vue', import.meta.url), 'utf8')
  .split('<script setup lang="ts">')[1].split('</script>')[0]
const ast = ts.createSourceFile('TaskList.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const names = ['loadTasks', 'switchScope', 'addTask']
const declarations = names.map(name => {
  const node = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === name)
  assert.ok(node, `组件函数 ${name} 必须存在`)
  return node.getText(ast)
}).join('\n')
const code = ts.transpileModule(declarations, {
  compilerOptions: { module: ts.ModuleKind.None, target: ts.ScriptTarget.ES2022 },
}).outputText
const guardSource = readFileSync(new URL('../src/utils/latestRequest.ts', import.meta.url), 'utf8')
const guardCode = ts.transpileModule(guardSource, {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 },
}).outputText
const { createLatestGuard } = await import(`data:text/javascript;base64,${Buffer.from(guardCode).toString('base64')}`)

function deferred() {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

function row(id, scope, parentId = null) {
  return { id, title: `任务 ${id}`, taskScope: scope, parentId, children: [] }
}

function harness({ post, fetchRows = async () => [], persist = async () => {}, flush = async () => {} } = {}) {
  const sent = []
  const ctx = vm.createContext({
    console,
    scopeVersion: 0, scopeSwitchGuard: createLatestGuard(), loadGuard: createLatestGuard(),
    taskScope: { value: 'COMPANY' }, tasks: { value: [] }, loading: { value: false },
    loadFailed: { value: false }, loadErrorMsg: { value: '' },
    editing: { value: null }, titleDraft: { value: '' }, categoryInputMode: { value: false },
    categoryDraft: { value: '' }, selectedId: { value: null }, selectedRows: { value: [] },
    expandedIds: { value: new Set() }, rowDepth: new Map(), page: { value: 1 },
    tableRef: { value: { setCurrentRow() {}, toggleRowExpansion() {} } },
    fromApi: value => value, buildTree: rows => rows,
    today: () => '2026-11-26', snapshot: () => new Map(), syncParents() {},
    persistDiff: persist, flushPendingEdit: flush, fetchAllTaskRows: fetchRows,
    httpPost: (_url, body) => { sent.push(body); return post ? post(body) : Promise.resolve(row(1, body.taskScope)) },
  })
  ctx.findTask = id => {
    const find = rows => {
      for (const item of rows) {
        if (item.id === id) return item
        const child = find(item.children)
        if (child) return child
      }
    }
    return find(ctx.tasks.value)
  }
  ctx.clearSelection = () => { ctx.selectedRows.value = [] }
  vm.runInContext(code, ctx)
  return { ctx, sent }
}

test('当前范围创建成功后仍正常插入、选中并编辑，子任务不提交独立范围', async () => {
  const parent = row(10, 'PERSONAL')
  const { ctx, sent } = harness({ post: async () => row(11, 'PERSONAL', 10) })
  ctx.taskScope.value = 'PERSONAL'
  ctx.tasks.value = [parent]
  await ctx.addTask(10)
  assert.equal(parent.children[0].id, 11)
  assert.equal(sent[0].parentId, 10)
  assert.equal(Object.hasOwn(sent[0], 'taskScope'), false)
  assert.equal(ctx.selectedId.value, 10)
  assert.equal(ctx.editing.value.id, 11)
})

test('公司任务创建晚返回时，不得插入已经加载的个人任务列表或抢占编辑态', async () => {
  const request = deferred()
  const personal = row(20, 'PERSONAL')
  const { ctx, sent } = harness({ post: () => request.promise, fetchRows: async () => [personal] })
  const adding = ctx.addTask(null)
  await ctx.switchScope('PERSONAL')
  ctx.editing.value = { id: 20, field: 'title' }
  ctx.selectedId.value = 20
  ctx.titleDraft.value = '个人草稿'
  request.resolve(row(10, 'COMPANY'))
  await adding
  assert.equal(sent[0].taskScope, 'COMPANY')
  assert.deepEqual(ctx.tasks.value.map(item => item.id), [20])
  assert.equal(ctx.editing.value.id, 20)
  assert.equal(ctx.selectedId.value, 20)
  assert.equal(ctx.titleDraft.value, '个人草稿')
})

test('离开后再返回同一范围，旧创建响应从服务端回读，不能重复插入或抢占编辑', async () => {
  const request = deferred()
  let companyRows = []
  const { ctx } = harness({
    post: () => request.promise,
    fetchRows: async scope => scope === 'COMPANY' ? companyRows : [],
  })
  const adding = ctx.addTask(null)
  await ctx.switchScope('PERSONAL')
  await ctx.switchScope('COMPANY')
  companyRows = [row(10, 'COMPANY')]
  request.resolve(companyRows[0])
  await adding
  assert.deepEqual(ctx.tasks.value.map(item => item.id), [10])
  assert.equal(ctx.selectedId.value, null)
  assert.equal(ctx.editing.value, null)
})

test('创建后的父进度保存期间切换范围，旧回调不得恢复旧任务的选中和标题编辑', async () => {
  const saving = deferred()
  const started = deferred()
  const { ctx } = harness({
    persist: () => { started.resolve(); return saving.promise },
    fetchRows: async () => [row(20, 'PERSONAL')],
  })
  const adding = ctx.addTask(null)
  await started.promise
  await ctx.switchScope('PERSONAL')
  saving.resolve()
  await adding
  assert.equal(ctx.tasks.value[0].id, 20)
  assert.equal(ctx.selectedId.value, null)
  assert.equal(ctx.editing.value, null)
})

test('切换时立即清除旧范围任务、单行选中和批量选择，不等待新列表返回', async () => {
  const loading = deferred()
  const old = row(10, 'COMPANY')
  const { ctx } = harness({ fetchRows: () => loading.promise })
  ctx.tasks.value = [old]
  ctx.selectedId.value = 10
  ctx.selectedRows.value = [old]
  ctx.page.value = 3
  await ctx.switchScope('PERSONAL')
  assert.equal(ctx.tasks.value.length, 0)
  assert.equal(ctx.selectedId.value, null)
  assert.equal(ctx.selectedRows.value.length, 0)
  assert.equal(ctx.page.value, 1)
  loading.resolve([])
})

test('保存旧草稿期间再次选择原范围，应取消先前尚未完成的切换', async () => {
  const saving = deferred()
  const { ctx } = harness({ flush: () => saving.promise })
  const switching = ctx.switchScope('PERSONAL')
  await ctx.switchScope('COMPANY')
  saving.resolve()
  await switching
  assert.equal(ctx.taskScope.value, 'COMPANY')
})
