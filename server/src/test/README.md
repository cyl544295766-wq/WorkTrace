# 任务范围回归测试

普通单元测试使用模拟 Mapper 和 HTTP 服务，不依赖数据库、不调用真实 AI：

```powershell
mvn '-Dtest=TaskServiceTest,InsightServiceTest' '-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true' test
```

`TaskScopeTransactionTest` 是可选的真实 MySQL 测试，默认跳过。它通过 Spring 事务代理和真实 MyBatis Mapper 验证：整树迁移、子任务拒绝，以及 UPDATE 实际执行后抛出异常时整树回滚。测试固定连接 `127.0.0.1`，必须指定非 3306 的临时端口；创建随机 `worktrace_scope_test_<UUID>` 库，结束时删除该库，不使用 `work_system`。

在独立临时容器中运行（已有本机 Docker 和 `mysql:8.4` 镜像，工作目录为 `server`）：

```powershell
docker run --rm --detach --name worktrace-scope-test --publish 127.0.0.1:13316:3306 --env MYSQL_ROOT_PASSWORD=worktrace_scope_test_only mysql:8.4
```

等待下面命令成功返回 `1` 后再运行测试；不要仅用 `mysqladmin ping` 判断初始化完成。如果端口已占用，选择另一个非 3306 的空闲端口并同步修改下方环境变量。

```powershell
docker exec --env MYSQL_PWD=worktrace_scope_test_only worktrace-scope-test mysql -uroot -N -e 'SELECT 1'

$env:WT_SCOPE_MYSQL_TEST='1'
$env:WT_SCOPE_MYSQL_PORT='13316'
$env:WT_SCOPE_MYSQL_PASSWORD='worktrace_scope_test_only'
try {
    mvn '-Dtest=TaskServiceTest,TaskScopeTransactionTest,InsightServiceTest' '-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true' test
} finally {
    Remove-Item Env:WT_SCOPE_MYSQL_PASSWORD,Env:WT_SCOPE_MYSQL_PORT,Env:WT_SCOPE_MYSQL_TEST -ErrorAction SilentlyContinue
    docker stop worktrace-scope-test
}
```

这里的密码仅用于新建临时容器，不应替换为生产凭据或读取已有容器的密码。测试容器使用 `--rm`，停止后自动删除；若测试进程被强制终止，也应停止这个临时容器。
