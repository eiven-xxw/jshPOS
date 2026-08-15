# 贝尔实验室 Spring 官方推荐镜像 JDK下载地址 https://bell-sw.com/pages/downloads/
FROM bellsoft/liberica-openjdk-rocky:17.0.16-cds
#FROM bellsoft/liberica-openjdk-rocky:21.0.8-cds
#FROM findepi/graalvm:java17-native

LABEL maintainer="Lion Li"

RUN groupadd --system --gid 10001 ruoyi \
    && useradd --system --uid 10001 --gid ruoyi --home-dir /ruoyi --shell /sbin/nologin ruoyi \
    && mkdir -p /ruoyi/snailjob/logs \
    && chown -R ruoyi:ruoyi /ruoyi

WORKDIR /ruoyi/snailjob

ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS=""

EXPOSE 8800
EXPOSE 17888

COPY --chown=ruoyi:ruoyi ./target/ruoyi-snailjob-server.jar ./app.jar

USER 10001:10001

SHELL ["/bin/bash", "-c"]

ENTRYPOINT java -Djava.security.egd=file:/dev/./urandom \
           -XX:+HeapDumpOnOutOfMemoryError -XX:+UseZGC ${JAVA_OPTS} \
           -jar app.jar
