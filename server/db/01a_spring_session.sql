-- ============================================================
-- 01a_spring_session.sql  Spring Session JDBC 官方表结构（MySQL 版）
-- ============================================================
-- ★ 本文件是 SPRING_SESSION 两张表的【唯一 DDL 来源】：应用配置
--   spring.session.jdbc.initialize-schema=never，启动期不执行任何建表 DDL；
--   全新环境由 Docker 首次初始化（docker-compose.yml 挂载本文件）或按
--   server/db/README.md 的顺序手工执行本文件。脚本幂等（IF NOT EXISTS），
--   在已有表/已有会话数据上重跑安全（不删表、不重建、不触碰数据行）。
--   未来表结构演进：新增编号分片（如 14_xxx.sql），存量环境按 db/README 升级约定执行。
-- 08_sys_seed.sql 中的 GRANT ... ON work_system.SPRING_SESSION 前置依赖本文件。
-- 来源：spring-session-jdbc 3.x org/springframework/session/jdbc/schema-mysql.sql
-- ============================================================

USE work_system;

CREATE TABLE IF NOT EXISTS SPRING_SESSION (
    PRIMARY_ID           CHAR(36)     NOT NULL,
    SESSION_ID           CHAR(36)     NOT NULL,
    CREATION_TIME        BIGINT       NOT NULL,
    LAST_ACCESS_TIME     BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT         NOT NULL,
    EXPIRY_TIME          BIGINT       NOT NULL,
    PRINCIPAL_NAME       VARCHAR(100) DEFAULT NULL,
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID),
    UNIQUE INDEX SPRING_SESSION_IX1 (SESSION_ID),
    INDEX SPRING_SESSION_IX2 (EXPIRY_TIME),
    INDEX SPRING_SESSION_IX3 (PRINCIPAL_NAME)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC
  COMMENT='Spring Session 会话主表';

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)      NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200)  NOT NULL,
    ATTRIBUTE_BYTES    BLOB          NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID)
        ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC
  COMMENT='Spring Session 会话属性（JDK 序列化存储）';
