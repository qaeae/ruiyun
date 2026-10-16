# 运行时镜像：单阶段，jar 由 CI 预先构建（避免镜像里带 Maven 与源码）
# 注意：构建前需先执行 mvn -pl wms-core -am package -DskipTests
FROM eclipse-temurin:17-jre-jammy

# 时区固定东八区：业务时间一律按 Asia/Shanghai 落库与展示（见 application.yml）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 非 root 运行（最小权限原则；容器逃逸时的第一道缓解）
RUN groupadd -r wms && useradd -r -g wms -d /app -s /sbin/nologin wms
WORKDIR /app
# 可执行 fat jar 带 boot classifier（原因见 wms-core/pom.xml 中 classifier 的说明，勿改成无 classifier）
COPY --chown=wms:wms wms-core/target/wms-core-*-boot.jar app.jar

USER wms
EXPOSE 8080

# 容器内存感知 + OOM 时保留堆转储入口；真实 JVM 参数由部署编排注入
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-Duser.timezone=Asia/Shanghai", \
  "-jar", "app.jar"]
