-- ============================================================
-- session-schema-mysql.sql  Spring Session JDBC 建表脚本（MySQL 语法）
-- ============================================================
-- 由 application.yml 的 spring.session.jdbc.initialize-schema=always 在启动时执行。
-- 服务器端同一份结构另存于 server/db/01a_spring_session.sql（容器首次初始化用）。
-- 脚本必须幂等：IF NOT EXISTS，重启安全。
-- 来源：spring-session-jdbc 3.x org/springframework/session/jdbc/schema-mysql.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100) DEFAULT NULL,
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID),
    UNIQUE INDEX SPRING_SESSION_IX1 (SESSION_ID),
    INDEX SPRING_SESSION_IX2 (EXPIRY_TIME),
    INDEX SPRING_SESSION_IX3 (PRINCIPAL_NAME)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK
        FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION (PRIMARY_ID)
        ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;
