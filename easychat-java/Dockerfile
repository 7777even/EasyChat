# EasyChat 后端镜像（多阶段：Maven 构建 → JRE 运行）
# 构建：docker build -f easychat-java/Dockerfile -t easychat-backend .
# 运行：docker run -p 5050:5050 -p 5051:5051 -v easychat-files:/data easychat-backend
#
# 2026-10-03：temurin-8 → temurin-17，与 pom 的 java.version=17、CI 的 temurin 17 三方对齐。

FROM maven:3.8-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom 预热依赖层，改 pom 不必重下全部依赖
COPY easychat-java/pom.xml .
RUN mvn -B -q dependency:go-offline

COPY easychat-java/src ./src
RUN mvn -B -q clean package -DskipTests


FROM eclipse-temurin:17-jre
WORKDIR /app

# --add-opens：Redisson FST 编码在 JDK 17 强封装下需要开放模块访问权限，与 pom 的 jvmArguments 同源。
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1g -Djava.security.egd=file:/dev/./urandom --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED"

# 以非 root 运行；/data 为文件落盘根目录（对应 project.folder）
RUN mkdir -p /data && groupadd -r easychat && useradd -r -g easychat easychat \
    && chown -R easychat:easychat /data
COPY --from=builder /build/target/easychat-1.0.jar /app/app.jar
RUN chown easychat:easychat /app/app.jar

USER easychat
EXPOSE 5050 5051

# 健康检查：/api 为 context-path，探一个存在的只读端点不合适（均需鉴权），
# 这里只验证 5050 端口可连，容器编排层面用 depends_on 串起 MySQL/Redis
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c ':> /dev/tcp/127.0.0.1/5050' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
