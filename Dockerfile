# ========== 构建阶段 ==========
# maven 官方镜像自带 JDK 17 + Maven
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 注入阿里云镜像加速依赖下载
COPY maven-settings.xml /root/.m2/settings.xml

# 先安装 reader-engine 本地依赖（避免每次改代码都重新安装）
COPY libs/reader-engine-1.0.0.jar /tmp/reader-engine.jar
RUN mvn install:install-file \
    -Dfile=/tmp/reader-engine.jar \
    -DgroupId=cn.kong \
    -DartifactId=reader-engine \
    -Dversion=1.0.0 \
    -Dpackaging=jar \
    -q

# 复制源码并构建（利用 Docker 层缓存，pom.xml 不变时跳过依赖下载）
COPY pom.xml .
RUN mvn dependency:resolve -q

COPY src ./src
RUN mvn clean package -q -DskipTests

# ========== 运行阶段 ==========
FROM eclipse-temurin:17-jre

WORKDIR /app

# 安装 curl（健康检查需要）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# 复制构建产物
COPY --from=builder /build/target/reader-1.0.0.jar app.jar

EXPOSE 8081

# 健康检查：验证 MCP 端点是否响应
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD curl -sf -X POST http://localhost:8081/mcp \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"healthcheck","version":"1.0.0"}}}' \
    || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
