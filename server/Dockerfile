# ── 阶段 1：Maven 构建 ──────────────────────────────
FROM maven:3.9-eclipse-temurin-17-jammy AS build
WORKDIR /app

# 先只拷贝 pom 拉依赖：源码变动时命中 Docker 层缓存，不必重新下载依赖
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ── 阶段 2：仅 JRE 运行时 ───────────────────────────
FROM eclipse-temurin:17-jre-jammy

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8
# JVM 与运行环境时区统一为 Asia/Shanghai（与 MySQL --default-time-zone=+08:00 对齐）
ENV JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# 非 root 运行
RUN useradd --system --uid 1001 worktrace
USER worktrace

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
