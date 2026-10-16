package com.wms.platform.modules.logistics.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.device.DeviceGateway;
import com.wms.platform.common.device.dto.PrintResultDto;
import com.wms.platform.common.device.dto.PrintTaskDto;
import com.wms.platform.common.idempotent.IdempotentService;
import com.wms.platform.common.idempotent.RequestHasher;
import com.wms.platform.modules.logistics.api.ro.LabelPrintRo;
import com.wms.platform.modules.logistics.api.vo.LabelPrintVo;
import com.wms.platform.modules.logistics.infrastructure.mapper.ShipmentPrintMapper;
import com.wms.platform.modules.logistics.infrastructure.po.ShipmentPrintRow;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 面单打印登记（03 API-LOG-03 / 02 M8-02），幂等键 {@code shpNo + printSeq}。
 *
 * <p><b>本类刻意不加 {@code @Transactional}</b>，这是设计决定而非疏漏：
 * 打印是**不可回滚的物理动作**——事务回滚无法把纸收回来。若把"下发打印 + 累加计数"放进一个事务，
 * 计数失败时事务回滚，但纸已经出了，结果反而更糟（库存级别的"账实不符"）。
 * 因此顺序固定为：
 * <pre>
 *   幂等占位(独立事务) → 查运单 → 下发打印(无事务) → 计数(单语句) → 回执
 * </pre>
 * 计数失败时**不回滚、不重打**，只记告警——宁可少记一次打印次数，也不能重复出纸。
 *
 * <p>三个幂等/一致性要点：
 * <ol>
 *   <li>幂等键用 {@code shpNo + printSeq}：重试同一序号不会重复出纸（端口层还有 taskId 去重兜底）；</li>
 *   <li>只有 {@code success=true} 才累加计数：失败也计数会让"补打次数"虚高，掩盖真实的重试问题；</li>
 *   <li>请求体哈希不一致 → 409：同一序号提交了不同内容（如改了份数），必须让调用方显式处理，
 *       不能"以第一次为准"静默返回。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class LabelPrintService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(LabelPrintService.class);

    /** 幂等业务类型（写入 pf_idempotent.biz_type）。 */
    private static final String BIZ_TYPE = "LABEL_PRINT";

    /** 运单打印 Mapper。 */
    private final ShipmentPrintMapper shipmentPrintMapper;

    /** 设备网关（打印端口入口）。 */
    private final DeviceGateway deviceGateway;

    /** 幂等裁决服务。 */
    private final IdempotentService idempotentService;

    /** JSON 序列化（缓存首次响应）。 */
    private final ObjectMapper objectMapper;

    /**
     * 打印登记（首次打印与补打共用；序号递增区分）。
     *
     * @param ro 打印入参
     * @return 打印登记结果（打印失败时 {@code success=false}，不抛异常）
     */
    public LabelPrintVo print(LabelPrintRo ro) {
        // 1. 幂等键 = shpNo + ':' + printSeq（03 API-LOG-03 约定）
        String idemKey = ro.shpNo() + ":" + ro.printSeq();

        // 2. 幂等裁决
        IdempotentService.Decision decision =
                idempotentService.begin(idemKey, BIZ_TYPE, RequestHasher.hash(ro));
        if (decision == IdempotentService.Decision.REPLAY) {
            // 2.1 完全相同的请求已成功过：直接返回首次结果，**不再出纸**
            return idempotentService.cachedResponse(idemKey)
                    .map(this::readCached)
                    .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "幂等缓存缺失：" + idemKey));
        }
        if (decision == IdempotentService.Decision.MISMATCH) {
            // 2.2 同序号不同内容：409（例如份数被改过），必须让调用方显式处理
            throw BizException.idempotentMismatch("同一打印序号提交了不同内容：" + idemKey);
        }
        if (decision == IdempotentService.Decision.IN_PROGRESS) {
            // 2.3 上一次仍在处理中：让调用方稍后重试
            throw BizException.conflict(ErrorCode.RATE_LIMITED, "该面单正在打印中，请稍后重试：" + idemKey);
        }

        // 3. 查运单（打印登记必须挂在一张真实运单上）
        ShipmentPrintRow row = shipmentPrintMapper.selectPrintInfo(TenantContext.tenantId(), ro.shpNo());
        if (row == null) {
            idempotentService.fail(idemKey);
            throw new BizException(ErrorCode.PARAM_ERROR, "装运单不存在：" + ro.shpNo());
        }

        // 4. 面单已作废不可打印（作废后重印会与承运商数据不一致）
        if (Boolean.TRUE.equals(row.getLabelVoid())) {
            idempotentService.fail(idemKey);
            throw new BizException(ErrorCode.PARAM_ERROR, "面单已作废，不可打印：" + ro.shpNo());
        }

        // 5. 下发打印任务（设备端口；打印是外部动作，故在事务之外执行）
        PrintTaskDto task = PrintTaskDto.of(idemKey, ro.shpNo(), row.getWaybillNo(),
                row.getLabelUrl(), ro.printerCode(), ro.copies());
        PrintResultDto printResult = deviceGateway.print(task);

        // 6. 打印失败：不抛异常（调用方可决定重试/换打印机/转人工），并标记幂等失败以便重试
        if (!printResult.success()) {
            idempotentService.fail(idemKey);
            log.warn("面单打印失败 shpNo={} printer={} reason={}",
                    ro.shpNo(), ro.printerCode(), printResult.message());
            return LabelPrintVo.of(ro.shpNo(), row.getWaybillNo(), ro.printerCode(),
                    0, row.getPrintCount() == null ? 0 : row.getPrintCount(), false, printResult.message());
        }

        // 7. 打印成功才累加计数（失败计数会让补打次数虚高）
        int sheets = ro.copies();
        int affected = shipmentPrintMapper.increasePrintCount(
                TenantContext.tenantId(), ro.shpNo(), sheets, TenantContext.userId());
        if (affected == 0) {
            // 7.1 计数失败不回滚、不重打：纸已出，重复出纸的代价更大
            log.error("面单已打印但计数失败（需人工核对） shpNo={} sheets={}", ro.shpNo(), sheets);
        }

        // 8. 组装结果并缓存首次响应
        int printCount = (row.getPrintCount() == null ? 0 : row.getPrintCount()) + sheets;
        LabelPrintVo vo = LabelPrintVo.of(ro.shpNo(), row.getWaybillNo(), ro.printerCode(),
                sheets, printCount, true, "ok");
        idempotentService.complete(idemKey, writeJson(vo));
        return vo;
    }

    /**
     * 反序列化缓存的首次响应。
     *
     * @param json 缓存 JSON
     * @return 打印登记结果
     */
    private LabelPrintVo readCached(String json) {
        try {
            return objectMapper.readValue(json, LabelPrintVo.class);
        } catch (Exception ex) {
            // 缓存损坏属于系统级问题：抛带码异常（B5），不返回半个对象
            throw new BizException(ErrorCode.SYSTEM_ERROR, "打印结果缓存解析失败", ex);
        }
    }

    /**
     * 序列化响应（缓存用）。
     *
     * @param vo 打印登记结果
     * @return JSON
     */
    private String writeJson(LabelPrintVo vo) {
        try {
            return objectMapper.writeValueAsString(vo);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "打印结果序列化失败", ex);
        }
    }
}
