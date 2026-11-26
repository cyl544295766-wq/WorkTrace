# WorkTrace 发布交接

更新日期：2026-11-26（Asia/Shanghai）。本文件记录**任务双范围（公司任务/个人任务）**的交付范围；上一轮首页改进与更早记录见文末沿革，历史八项优化的提示词和当时验证边界见根目录 `优化提示词与核实清单.txt`，历史结果不代表当前环境已重新验收。

## 本次改动（任务双范围 task_scope）

- **数据结构**：`biz_task.task_scope`、`biz_report.task_scope` 新增列（`VARCHAR(16) NOT NULL DEFAULT 'COMPANY'`，取值 COMPANY/PERSONAL，独立字段不复用 `category`）。迁移脚本 `server/db/14_task_scope.sql` 幂等（缺列才加、绝不全表回写，后建 PERSONAL 不会被改回），生产操作手册 `deploy/MIGRATION-task-scope.md`。
- **任务管理页**：顶部「公司任务/个人任务」切换（默认公司任务），切换后树/统计/搜索/筛选/分页/批量/空态全部限定当前范围；未保存行内编辑先冲刷、分页勾选编辑态重置、`createLatestGuard` 丢弃晚返回的旧范围响应。行操作新增「调范围」走 `PUT /api/tasks/{id}/scope` 整棵子树迁移并提示受影响条数（子任务不能单独迁移，`@Transactional` 失败整体回滚）。
- **后端**：`TaskScope` 常量/normalize（非法值 400、缺省 COMPANY）；create 校验父任务归属+范围（跨范围挂接 400、子任务强制继承）；update 未传范围保留原值、传不同值 400 提示走迁移接口；`page`/`scroll` 按 user+scope 过滤（游标排序与 size+1 hasMore 不变）；迁移接口整树 UPDATE 限 userId，并在幂等判断前拒绝直接传入子任务 ID（400）。所有接口范围参数从不绕过 user 归属校验。
- **首页与 AI**：首页同款范围开关（默认公司任务），KPI/走势/今日聚焦/洞察同范围并标注来源；洞察统计/分类/样例/缓存键（`userId|scope`）/响应快照全带范围，prompt 注入「统计范围：」。AI 报告 `taskScope` 默认 COMPANY（个人需显式选择），统计聚合/祖先骨架/明细日志/任务树游走限定 user+scope，`biz_report` 保存范围、历史报告归 COMPANY。
- **异步创建与切换修复**：创建请求捕获当前范围与切换版本；切换后旧创建回调不再插入列表或恢复旧编辑态，离开再返回同一范围时从服务端回读补齐。切换即清空旧任务和单行/批量选择；保存草稿期间再次选择范围，以最后一次选择为准。
- **前端类型与测试**：`TaskScope` 类型与 `scopeLabel`、`InsightStats.scope`、`ReportItem.taskScope`；`latestRequest.ts` 竞态守卫 + `npm run test:scope`（10 项 node:test，包含直接运行组件实际异步函数的回归测试）。

## 验证记录（按证据等级分层）

### 本轮复核（2026-11-26）

- **后端测试 42/42**：`InsightServiceTest` 19 项 + `TaskServiceTest` 20 项单元测试全部通过；另有 `TaskScopeTransactionTest` 3 项真实 MySQL 集成测试通过。包括直接传子任务 ID 拒绝、同范围子任务也不能绕过顶层校验。
- **真实事务回滚**：在新建临时 MySQL 容器的随机独立测试库中，通过 Spring 事务代理 + 真实 MyBatis Mapper 执行 UPDATE 后抛异常，确认父/子/孙范围全部恢复；成功迁移保持其他用户及任务字段不变。未连接现有业务库或生产库，运行方法见 `server/src/test/README.md`。
- **前端 21/21**：`npm run test:scope` 10/10、`npm run test:focus` 11/11；`npm run build`（含 `vue-tsc`）成功。Vite 大包提示仍存在。
- **回归有效性**：修复前新增的两个后端子任务测试及五个前端异步场景均失败，修复后通过。
- **文档**：迁移手册已统一为先迁移数据库、核对，再发布新应用。

### 此前本地验证记录（本轮未重复执行）

- **迁移幂等（本地 MySQL 实测）**：root 执行 `14` 两遍——首次加列后存量全 COMPANY、行数不变；插入 PERSONAL 行重跑，两列均「already exists, skip」、PERSONAL 保留、计数不变。`wt_app` 执行报 `ERROR 1142`（无 ALTER），已写入手册。
- **端到端（本地 jar + 真库冒烟）**：scroll COMPANY/PERSONAL 200；page 非法范围 400；migrate 不存在/跨用户 404、非法目标 400；建 COMPANY 父子（子继承）、跨范围挂接 400、`migrated=2` 后两范围列表正确、重复迁移 `migrated=0`、普通 update 改范围 400、洞察响应回显 scope。测试数据已清理，基线恢复（1 用户/4 任务/2 报告/2 日志）。

### 模拟验证

- >500 行范围分页由 `scrollPagesBeyond500WithHasMore`（501 行 mock → LIMIT 501、hasMore、游标推进）+ LIMIT 501 冒烟覆盖，**未在真实 5 万行数据集上跑生产量级分页**。
- 快速切换竞态由 `test:scope` 10 项测试覆盖（旧加载丢弃、创建晚返回、切换后返回同范围、保存期间切换与取消），**未做浏览器级并发切换压测**。
- 前端交互（范围切换 UI、调范围弹窗、报告范围单选）以类型检查+构建+接口冒烟保证，**未做完整浏览器 E2E 遍历**。

### 待生产验证（另行安排，本次未执行）

- **生产数据库迁移**：按 `deploy/MIGRATION-task-scope.md` 备份 → 停应用 → root 执行 `14` → 核对（存量全 COMPANY、行数/父子/日志不变）。
- **正式发布**：`bash deploy/publish.sh`（**必须先迁移库再发布**——新代码查询带 `task_scope`，列不存在会 500；旧代码容忍新列）。
- 发布后线上抽查：任务页双范围切换、首页范围一致性、生成一份报告确认 `biz_report.task_scope`。

## 发布方式与环境

- 分支：`master`；远端：`github`（GitHub 仅托管代码，不会因 push 自动部署）。
- 唯一发布入口：本机 Git Bash 执行 `bash deploy/publish.sh`。脚本构建时跳过测试执行，因此必须单独执行上面的回归测试。
- **本次上线前置（顺序不能反）**：先按 `deploy/MIGRATION-task-scope.md` 以 root/DBA 执行生产库 `14_task_scope.sql` 并核对，**再** `publish.sh` 发布——新代码查询带 `task_scope`，列不存在会 500；旧代码容忍新列（DEFAULT COMPANY）。误先发布则立即补跑 `14` 即可（幂等）。
- 访问入口：<http://140.143.166.155:8081/>，按现有 HTTP 部署。
- 服务：`worktrace.service`；后端 `/home/ubuntu/worktrace/app.jar`，健康接口仅服务器本机 `http://127.0.0.1:8083/actuator/health`。
- 前端：`/var/www/worktrace`。只发布 WorkTrace，不调整同机其他项目或数据库结构。
- 部署凭据保持在本地忽略文件和服务器受限环境文件中，不写入本交接文档。

## 发布记录

状态：2026-11-26 10:18（Asia/Shanghai）发布成功。源码提交 `7ac2ce614e8387b1fc86802e39c787793e8ea47b` 已推送到 `github/master`；本记录随后以文档提交补齐，线上产物对应上述源码提交。

- `deploy/publish.sh` 全部六步执行成功；后端构建、上传 MD5 比对、前端发布及重启健康检查通过。
- `worktrace.service` 为 `active`，本次启动时间 `2026-11-26 10:18:11 CST`；健康响应为 `{"status":"UP"}`。
- 本地与服务器 SHA256 一致：后端 jar `5512f7d072d8302f6bdeb937872e44879acf94302f68f0cbcf22d79f7c7f482b`；前端 `index.html` `2e9a437b67830b10288a6a623dd31ade2841c7e3dc614bb36851ddedf02d89df`。
- 线上入口可访问，未携带登录态访问 `/api/tasks/scroll` 返回 HTTP 401。
- 已登录用户真实首页只读检查通过：1174×736 下今日聚焦顶部约 377px，第三条任务底部约 703px；总数 115、进行中 5、完成 92、待处理 18，与页面洞察的未完成 23 一致。390px 下按单列显示，没有横向溢出。
- 浏览器最初保留旧页面，绕过缓存刷新后加载新入口 `/assets/index-iXQbL5qY.js`。如仍显示旧版，使用 `Ctrl+F5` 强制刷新。
- 同版发布前备份目录：`/home/ubuntu/worktrace/backups/release-20261126101653/`，包含 `app.jar` 与 `web.tar.gz`；脚本额外生成后端备份 `/home/ubuntu/worktrace/backups/app.jar.bak-20261126101737`。
- 本次无数据库迁移、无业务数据写入；线上仅检查正常首页与访问保护，未点击任务完成、重新生成洞察或报告按钮。

## 回退说明

发布脚本保留最近三份后端 jar；只有重启后的健康检查失败分支会自动恢复 jar，前端不会自动回退。前端发布阶段失败也不会自动回退。

本次前后端备份路径见发布记录。需要整版回退时，先确认备份对应同一发布前版本，恢复 jar 和前端目录后重启 `worktrace`，再检查健康接口及外网首页。不要覆盖其他应用目录。该额外备份独立于脚本的三份 jar 保留策略，确认版本稳定后可手动清理。

## 后续事项

- **本次（任务双范围）生产迁移与发布尚未执行**——见上方「待生产验证」，操作步骤在 `deploy/MIGRATION-task-scope.md`。
- 首页“新建任务”及任务标题目前导航到任务列表；尚未提供直接打开创建窗口或定位具体任务的交互。
- 首页仍完整加载任务后计算图表，大数据量性能可后续改为专用统计接口；宽屏 KPI 装饰条仍不代表真实比例。
- 此次未重新执行历史八项优化的所有生产场景。正常打开首页会调用洞察接口，但未专门进行真实模型输出验收、主动重新生成洞察、付费报告生成或业务数据写入验收。

## 历史沿革（上一轮：首页改进，已发布）

2026-11-26 10:18 发布成功（提交 `7ac2ce6`，jar MD5 `5512f7d0`，备份见下方发布记录）：首页紧凑问候/统计区、今日聚焦排序与补位、AI 洞察真实统计+规则降级+缓存核对、README 同步。当轮验证：`test:focus` 11 项、`InsightServiceTest` 17 项、本地浏览器 1174/1366/900/390px 布局。该轮产物即当前线上版本。
