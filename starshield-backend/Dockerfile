FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 容器内没有 ~/.m2/toolchains.xml，需指向镜像自带的 JDK 17
RUN mkdir -p /root/.m2 && \
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">\n  <toolchain>\n    <type>jdk</type>\n    <provides><version>17</version></provides>\n    <configuration><jdkHome>%s</jdkHome></configuration>\n  </toolchain>\n</toolchains>\n' \
    "$JAVA_HOME" > /root/.m2/toolchains.xml

COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 复用构建阶段已能拉取的 Temurin 基础镜像，避免部分 Docker 镜像站对
# eclipse-temurin:17-jre-jammy 返回 429 导致本地演示构建失败。
FROM maven:3.9-eclipse-temurin-17
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl python3 python3-pip \
    && pip3 install --break-system-packages --no-cache-dir "requests>=2.31" "websocket-client>=1.6.0" "brotli>=1.0.9" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r starshield && useradd -r -g starshield starshield
COPY --from=build /app/target/starshield-backend-*.jar /app/app.jar
USER starshield
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
