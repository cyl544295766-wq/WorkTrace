-- ============================================================
-- 01a_spring_session.sql  Spring Session JDBC 官方表结构（MySQL 版）
-- ============================================================
-- spring-session-jdbc 在 MySQL 上不会自动建表（initialize-schema 默认仅对
-- 内嵌库生效），必须在初始化阶段手工创建，否则 Session 落库报错。
-- 08_sys_seed.sql 中的 GRANT ... ON work_system.SPRING_SESSION 前置依赖本文件。
-- 来源：spring-session-jdbc 3.x org/springframework/session/jdbc/schema-mysql.sql
-- ============================================================

USE work_system;

CREATE TABLE SPRING_SESSION (
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

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)      NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200)  NOT NULL,
    ATTRIBUTE_BYTES    BLOB          NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID)
        ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC
  COMMENT='Spring Session 会话属性（JDK 序列化存储）';
