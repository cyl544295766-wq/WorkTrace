package com.worktrace.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.worktrace.common.api.Result;
import com.worktrace.common.exception.BizException;
import com.worktrace.task.TaskScope;
import com.worktrace.task.mapper.BizTaskLogMapper;
import com.worktrace.task.mapper.BizTaskMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 可选真实 MySQL 集成测试：只连接本机临时 MySQL 容器，创建随机独立测试库并在结束时删除。
 * 设置 WT_SCOPE_MYSQL_TEST=1、WT_SCOPE_MYSQL_PORT、WT_SCOPE_MYSQL_PASSWORD 后运行；不会连接 work_system。
 */
@EnabledIfEnvironmentVariable(named = "WT_SCOPE_MYSQL_TEST", matches = "1")
class TaskScopeTransactionTest {

    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static BizTaskMapper realMapper;
    private static String database;
    private final AtomicBoolean failAfterUpdate = new AtomicBoolean();
    private TaskService service;

    @BeforeAll
    static void createIsolatedDatabase() throws Exception {
        String password = System.getenv("WT_SCOPE_MYSQL_PASSWORD");
        assertNotNull(password, "请提供本机测试 MySQL 密码，不允许使用生产数据库");
        int port = Integer.parseInt(System.getenv("WT_SCOPE_MYSQL_PORT"));
        assertTrue(port >= 1024 && port <= 65535 && port != 3306,
                "必须显式提供临时 MySQL 端口，禁止连接现有 3306 业务数据库");
        String mysql = "jdbc:mysql://127.0.0.1:" + port + "/";
        String user = System.getenv().getOrDefault("WT_SCOPE_MYSQL_USER", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql + OPTIONS, user, password));
        database = "worktrace_scope_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database + "`");
        dataSource = new DriverManagerDataSource(mysql + database + OPTIONS, user, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE biz_task (
                  id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, title VARCHAR(255), content TEXT,
                  status INT NOT NULL, progress INT, priority INT, plan_date DATE, actual_hours DECIMAL(10,2),
                  parent_id BIGINT, category VARCHAR(32), is_key INT, task_scope VARCHAR(16) NOT NULL,
                  finish_time DATETIME(3), create_time DATETIME(3), update_time DATETIME(3)
                ) ENGINE=InnoDB
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sessions = factory.getObject();
        assertNotNull(sessions);
        sessions.getConfiguration().addMapper(BizTaskMapper.class);
        realMapper = new SqlSessionTemplate(sessions).getMapper(BizTaskMapper.class);
    }

    @AfterAll
    static void dropIsolatedDatabase() {
        if (database != null) {
            // 清理只允许本测试生成的随机库名，绝不对业务库执行 DROP。
            assertTrue(database.matches("worktrace_scope_test_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @BeforeEach
    void seedAndCreateTransactionalService() {
        jdbc.update("DELETE FROM biz_task");
        seed(10L, 100L, null);
        seed(11L, 100L, 10L);
        seed(12L, 100L, 11L);
        seed(50L, 200L, null);
        BizTaskMapper mapper = (BizTaskMapper) Proxy.newProxyInstance(
                BizTaskMapper.class.getClassLoader(), new Class<?>[]{BizTaskMapper.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(realMapper, args);
                        if (method.getName().equals("update") && failAfterUpdate.get()) {
                            // 先真实更新数据库，再抛出异常，验证 Spring 事务确实撤销已发生的写入。
                            throw new IllegalStateException("fail after database update");
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ProxyFactory proxy = new ProxyFactory(new TaskService(mapper, mock(BizTaskLogMapper.class)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (TaskService) proxy.getProxy();
    }

    private void seed(Long id, Long userId, Long parentId) {
        jdbc.update("""
                INSERT INTO biz_task
                  (id,user_id,title,status,progress,priority,parent_id,is_key,task_scope,create_time,update_time)
                VALUES (?,?,?,0,25,2,?,1,'COMPANY',NOW(3),NOW(3))
                """, id, userId, "scope test", parentId);
    }

    @Test
    void migratesWholeTreeWithoutChangingOtherUsersOrTaskFields() {
        List<?> before = jdbc.queryForList("SELECT id,parent_id,status,progress,is_key FROM biz_task ORDER BY id");
        assertEquals(3, service.migrateScope(100L, 10L, TaskScope.PERSONAL));
        assertEquals(List.of("PERSONAL", "PERSONAL", "PERSONAL"), ownedScopes());
        assertEquals("COMPANY", jdbc.queryForObject("SELECT task_scope FROM biz_task WHERE id=50", String.class));
        assertEquals(before, jdbc.queryForList("SELECT id,parent_id,status,progress,is_key FROM biz_task ORDER BY id"));
    }

    @Test
    void exceptionAfterRealUpdateRollsBackEntireTree() {
        failAfterUpdate.set(true);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.migrateScope(100L, 10L, TaskScope.PERSONAL));
        assertEquals("fail after database update", error.getMessage());
        assertEquals(List.of("COMPANY", "COMPANY", "COMPANY"), ownedScopes());
    }

    @Test
    void childMigrationIsRejectedAndLeavesDatabaseUnchanged() {
        BizException error = assertThrows(BizException.class,
                () -> service.migrateScope(100L, 11L, TaskScope.PERSONAL));
        assertEquals(Result.BAD_REQUEST, error.getCode());
        assertEquals(List.of("COMPANY", "COMPANY", "COMPANY"), ownedScopes());
    }

    private List<String> ownedScopes() {
        return jdbc.queryForList("SELECT task_scope FROM biz_task WHERE user_id=100 ORDER BY id", String.class);
    }
}
