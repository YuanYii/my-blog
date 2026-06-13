# =============================================================
# 后端多阶段构建：maven build → jre runtime
# 上下文：build 时在 backend/ 目录执行
#   docker build -f backend/blog-app/Dockerfile -t myblog-backend:latest backend
# =============================================================

# ---------- Stage 1: build ----------
FROM maven:3.8-openjdk-8 AS build
WORKDIR /app

# 先 copy 所有 pom.xml（多模块）
# 利用 Docker layer 缓存：依赖没变就跳过 mvn download
COPY pom.xml /app/pom.xml
COPY blog-common/pom.xml /app/blog-common/
COPY blog-auth/pom.xml /app/blog-auth/
COPY blog-article/pom.xml /app/blog-article/
COPY blog-comment/pom.xml /app/blog-comment/
COPY blog-settings/pom.xml /app/blog-settings/
COPY blog-app/pom.xml /app/blog-app/

# 预下载依赖（如果 network 限制可改为阿里云镜像）
RUN mvn -B -q -DskipTests -f /app/pom.xml dependency:go-offline || \
    mvn -B -DskipTests -f /app/pom.xml dependency:go-offline

# 再 copy 源码
COPY blog-common/ /app/blog-common/
COPY blog-auth/ /app/blog-auth/
COPY blog-article/ /app/blog-article/
COPY blog-comment/ /app/blog-comment/
COPY blog-settings/ /app/blog-settings/
COPY blog-app/ /app/blog-app/

# 打包 blog-app（含依赖，Spring Boot fat jar）
RUN mvn -B -DskipTests -f /app/pom.xml -pl blog-app -am clean package

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:8-jre-alpine
WORKDIR /app

# 时区 + 中文支持
RUN apk add --no-cache tzdata curl && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 创建日志目录
RUN mkdir -p /var/log/blog

# 拷贝 fat jar
COPY --from=build /app/blog-app/target/blog-app-*.jar /app/app.jar

EXPOSE 8080

# JVM 参数 + 启动命令
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom"

# 启动时强制走 prod profile
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=prod"]

# 健康检查（容器内）—— 用 curl（apk add 装的是 curl 不是 wget）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/v1/health || exit 1
