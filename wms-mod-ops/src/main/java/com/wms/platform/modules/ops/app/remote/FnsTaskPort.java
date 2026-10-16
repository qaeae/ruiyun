package com.wms.platform.modules.ops.app.remote;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 财务服务任务端口（P3.4：任务通过它调用财务服务，隐藏 HTTP 细节）。
 *
 * <p><b>为什么抽接口</b>：
 * <ul>
 *   <li>任务只依赖"能做什么"，不依赖"怎么调"（HTTP/服务名/凭据都封在实现里）；</li>
 *   <li><b>测试可以注入替身实现</b>，不必依赖 Mockito 的注入时序——
 *       实测 {@code @MockitoBean} 在"任务壳 + 并发线程"场景下未必能拦到调用，
 *       而一个显式的 {@code @TestConfiguration} 替身 Bean 是确定的。</li>
 * </ul>
 */
public interface FnsTaskPort {

    /**
     * 执行日终四层勾稽。
     *
     * @param bizDate 业务日期（T-1）
     * @return 各层结果（HTTP 契约字段：layer/status/…）
     */
    List<Map<String, Object>> runDailyReconciliation(LocalDate bizDate);

    /**
     * 执行兜底扣减扫描。
     *
     * @return 候选运单列表（字段：waybillNo/alreadyDeducted/…）
     */
    List<Map<String, Object>> sweepFallback();
}
