package com.wms.platform.poc;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * PoC 集成测试的运行条件：**有外部一次性 MySQL 或本机 Docker 才跑，否则跳过（不算失败）**。
 *
 * <p>为什么需要它而不是用 {@code Assumptions.abort}：{@code @SpringBootTest} 会在
 * {@code @BeforeAll} **之前**创建上下文（连数据库、跑 Flyway）。若在 {@code @BeforeAll} 里 abort，
 * 上下文创建早已失败，测试会"红"而不是"跳过"，本机没 Docker 的人就永远看到一条假失败。
 * {@link ExecutionCondition} 在上下文创建之前就被求值，因此能真正跳过。
 *
 * <p>用法：{@code @ExtendWith(PocMysqlCondition.class)}
 */
public class PocMysqlCondition implements ExecutionCondition {

    /** 外部库地址系统属性。 */
    private static final String URL_PROPERTY = "poc.mysql.url";

    /** 外部库地址环境变量。 */
    private static final String URL_ENV = "POC_MYSQL_URL";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // 1. 优先用外部一次性 MySQL（本机验证用，不碰在用实例）
        String url = System.getProperty(URL_PROPERTY, System.getenv(URL_ENV));
        if (url != null && !url.isBlank()) {
            return ConditionEvaluationResult.enabled("使用外部 MySQL：" + url + "（来源 -D" + URL_PROPERTY + "）");
        }
        // 2. 其次 Docker 可用（CI 场景：Testcontainers 起临时库）
        if (DockerClientFactory.instance().isDockerAvailable()) {
            return ConditionEvaluationResult.enabled("Docker 可用，使用 Testcontainers");
        }
        // 3. 都没有：跳过（不算失败），并打印可执行的补救命令
        return ConditionEvaluationResult.disabled("未提供 -D" + URL_PROPERTY + " 且本机无 Docker，跳过 PoC 集成测试"
                + "（如需本机运行：-D" + URL_PROPERTY + "=jdbc:mysql://127.0.0.1:3399/wms_poc -D"
                + "poc.mysql.user=root -Dpoc.mysql.password=）");
    }
}
