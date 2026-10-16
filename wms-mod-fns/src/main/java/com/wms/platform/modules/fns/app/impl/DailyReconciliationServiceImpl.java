package com.wms.platform.modules.fns.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.fns.api.dto.ChannelReconcileFile;
import com.wms.platform.modules.fns.api.vo.DailyReconciliationVo;
import com.wms.platform.modules.fns.app.DailyReconciliationService;
import com.wms.platform.modules.fns.domain.DailyReconcileLayer;
import com.wms.platform.modules.fns.domain.DailyReconcileRule;
import com.wms.platform.modules.fns.domain.ReconcileDiffRule;
import com.wms.platform.modules.fns.infrastructure.mapper.DailyReconcileSourceMapper;
import com.wms.platform.modules.fns.infrastructure.mapper.FnDailyReconciliationMapper;
import com.wms.platform.modules.fns.infrastructure.po.FnDailyReconciliationPo;
import com.wms.platform.modules.fns.app.remote.InventoryInvariantClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日终勾稽服务实现（{@code 04} §5.2，`02` M9-05）。
 *
 * <p>规范落地：B1、B4、B5、B7、B9。
 *
 * <p><b>实现口径（重要）</b>：
 * <ul>
 *   <li><b>资金层</b>：平台侧 = `ac_merchant_account`（余额+冻结+押金）逐商家汇总；对方侧 = 存管账户对账文件。
 *       文件未提供 → **待接入**（不是"有差异"，理由见 {@code DailyReconcileRule}）；</li>
 *   <li><b>业务层</b>：平台侧 = 当日费用单合计（已确认/已扣减）；对方侧 = 资金流水扣减合计。
 *       资金域未落地时对方侧恒为 0，会如实报"有差异"——**这是正确的**（费用确实还没扣）；</li>
 *   <li><b>三方层</b>：逐单比平台运费 vs 承运商账单，**复用已确认的阈值口径**
 *       （{@code ReconcileDiffRule}：≤1 元或 ≤1% 自动平账，超阈值计入差异笔数）；</li>
 *   <li><b>库存层</b>：直接调用 `iv.app.InventoryService.checkInvariants()`（三态不变量 + 两级账），
 *       **不重复实现**——重复实现等于两套真相，迟早不一致。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DailyReconciliationServiceImpl implements DailyReconciliationService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationServiceImpl.class);

    /** 勾稽记录 Mapper。 */
    private final FnDailyReconciliationMapper dailyMapper;

    /** 取数（跨域只读）。 */
    private final DailyReconcileSourceMapper sourceMapper;

    /** 事务管理器：远程调用必须在事务外，因此写入部分用 TransactionTemplate 显式框定。 */
    private final PlatformTransactionManager transactionManager;

    /** 库存域 app 服务（库存层复用其不变量校验）。 */
    private final InventoryInvariantClient inventoryInvariantClient;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public List<DailyReconciliationVo> run(LocalDate bizDate, ChannelReconcileFile channelFile) {
        // 0. 入参校验先行：坏入参不该先打远端（也保证"空日期"这类错误语义稳定）
        if (bizDate == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "业务日期不能为空");
        }
        // 第⑤关（拆分踩坑）：**远程调用放在事务外**。
        // 若在事务内调用，会一边持有本地事务的行锁、一边等远端返回；
        // 远端一旦因为同一批数据被本事务锁住而变慢，就形成"持锁等远端"的互相等待，
        // 实测表现：core 直连同一接口 0.014s，而经本服务在事务内调用 10s 读超时。
        List<String> inventoryViolations = inventoryInvariantClient.describeViolations();
        return new TransactionTemplate(transactionManager)
                .execute(status -> runInTransaction(bizDate, channelFile, inventoryViolations));
    }

    /**
     * 事务内执行：写本地对账结果（远程数据已在上一步取回）。
     *
     * @param bizDate 业务日期
     * @param channelFile 渠道对账文件
     * @param inventoryViolations 库存不变量违反明细（事务外取回）
     * @return 各层结果
     */
    private List<DailyReconciliationVo> runInTransaction(LocalDate bizDate, ChannelReconcileFile channelFile,
                                                         List<String> inventoryViolations) {
        long tenant = TenantContext.tenantId();
        if (bizDate == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "业务日期不能为空");
        }
        // 1. 四层依次勾稽
        List<DailyReconciliationVo> results = new ArrayList<>(4);
        results.add(reconcileFund(tenant, bizDate, channelFile));
        results.add(reconcileFee(tenant, bizDate, channelFile));
        results.add(reconcileCarrier(tenant, bizDate, channelFile));
        results.add(reconcileInventory(tenant, bizDate, inventoryViolations));
        // 2. 汇总告警（差异必须显眼：T+1 告警口径）
        long diffLayers = results.stream()
                .filter(vo -> DailyReconcileRule.STATUS_DIFF.equals(vo.status())).count();
        long waitingLayers = results.stream()
                .filter(vo -> DailyReconcileRule.STATUS_WAITING_CHANNEL.equals(vo.status())).count();
        if (diffLayers > 0) {
            log.error("日终勾稽发现差异 bizDate={} 差异层数={} 明细={}", bizDate, diffLayers, results);
        } else if (waitingLayers > 0) {
            log.warn("日终勾稽完成 bizDate={} 但 {} 层「待接入」（缺通道/承运商侧数据，无法对账）", bizDate,
                    waitingLayers);
        } else {
            log.info("日终勾稽完成 bizDate={} 四层全部一致", bizDate);
        }
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyReconciliationVo> query(LocalDate bizDate) {
        long tenant = TenantContext.tenantId();
        List<FnDailyReconciliationPo> rows = dailyMapper.selectByDate(tenant, bizDate);
        List<DailyReconciliationVo> vos = new ArrayList<>(rows.size());
        for (FnDailyReconciliationPo row : rows) {
            vos.add(toVo(row));
        }
        return vos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(long id, String remark) {
        long tenant = TenantContext.tenantId();
        // 1. 取记录（Mapper 侧已按租户过滤；不存在即报错）
        FnDailyReconciliationPo row = dailyMapper.selectByIdSafe(tenant, id);
        if (row == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "勾稽记录不存在：" + id);
        }
        // 2. 领域校验：一致/已处理的不能标记，说明必填
        DailyReconcileRule.assertHandleable(row.getStatus(), remark);
        // 3. 条件更新（仅「有差异」可标记）
        int affected = dailyMapper.markHandled(tenant, id, remark, TenantContext.userId());
        if (affected == 0) {
            throw new BizException(ErrorCode.RECONCILE_STATUS_NOT_ALLOW_CONFIRM,
                    "该层状态已变化，请刷新后重试（仅「有差异」可标记已处理）");
        }
        log.info("日终勾稽差异已处理 id={} layer={} 说明={}", id, row.getLayer(), remark);
    }

    // ===================== 四层实现 =====================

    /**
     * 资金层：平台账 vs 存管账户对账文件（逐商家）。
     *
     * @param tenantId    租户
     * @param bizDate     业务日期
     * @param channelFile 通道文件（可空）
     * @return 勾稽结果
     */
    private DailyReconciliationVo reconcileFund(long tenantId, LocalDate bizDate, ChannelReconcileFile channelFile) {
        // 1. 平台侧：逐商家汇总
        Map<Long, BigDecimal> platform = new HashMap<>();
        BigDecimal platformTotal = BigDecimal.ZERO;
        for (DailyReconcileSourceMapper.MerchantAmountRow row : sourceMapper.selectMerchantTotals(tenantId)) {
            BigDecimal amount = row.total() == null ? BigDecimal.ZERO : row.total();
            platform.put(row.merchantId(), amount);
            platformTotal = platformTotal.add(amount);
        }
        // 2. 对方侧：通道文件（可空 → 待接入）
        boolean hasFile = channelFile != null && channelFile.merchantBalances() != null;
        BigDecimal channelTotal = BigDecimal.ZERO;
        List<String> details = new ArrayList<>();
        long diffCount = 0;
        if (hasFile) {
            Map<Long, BigDecimal> channel = channelFile.merchantBalances();
            for (Map.Entry<Long, BigDecimal> entry : channel.entrySet()) {
                channelTotal = channelTotal.add(entry.getValue());
                BigDecimal ours = platform.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (ours.compareTo(entry.getValue()) != 0) {
                    diffCount++;
                    details.add("商家 " + entry.getKey() + "：平台 " + ours + " vs 通道 " + entry.getValue());
                }
            }
            // 平台有、通道文件里没有的商家也是差异（文件漏记同样是问题）
            for (Map.Entry<Long, BigDecimal> entry : platform.entrySet()) {
                if (!channel.containsKey(entry.getKey())) {
                    diffCount++;
                    details.add("商家 " + entry.getKey() + "：平台 " + entry.getValue() + " vs 通道文件缺失");
                }
            }
        }
        return save(tenantId, bizDate, DailyReconcileLayer.FUND, platformTotal, channelTotal,
                platformTotal.subtract(channelTotal), (int) diffCount, hasFile, details);
    }

    /**
     * 业务层：费用单合计 vs 资金流水扣减合计。
     *
     * @param tenantId    租户
     * @param bizDate     业务日期
     * @param channelFile 通道文件（可空：未提供时对方侧取平台内的资金流水，仍可对）
     * @return 勾稽结果
     */
    private DailyReconciliationVo reconcileFee(long tenantId, LocalDate bizDate, ChannelReconcileFile channelFile) {
        // 1. 平台侧：当日费用单合计（已确认/已扣减）
        BigDecimal platformAmount = sourceMapper.selectFeeOrderTotal(tenantId, bizDate);
        // 2. 对方侧：当日资金流水扣减合计（**取自我们自己的资金流水表**，与通道文件无关，
        //    所以即便通道未接入，"该扣的是否真扣了"这一层依然可以对——这正是分层的价值）
        BigDecimal channelAmount = sourceMapper.selectLedgerDeductTotal(tenantId, bizDate);
        BigDecimal diff = platformAmount.subtract(channelAmount);
        List<String> details = new ArrayList<>();
        if (diff.signum() != 0) {
            details.add("费用单合计 " + platformAmount + " vs 资金流水扣减合计 " + channelAmount
                    + "（差额为正 = 有费用未扣减；为负 = 可能重复扣减）");
        }
        return save(tenantId, bizDate, DailyReconcileLayer.FEE, platformAmount, channelAmount, diff,
                diff.signum() == 0 ? 0 : 1, true, details);
    }

    /**
     * 三方层：逐单平台运费 vs 承运商账单（复用已确认的差异阈值口径）。
     *
     * @param tenantId    租户
     * @param bizDate     业务日期
     * @param channelFile 通道文件（可空 → 待接入）
     * @return 勾稽结果
     */
    private DailyReconciliationVo reconcileCarrier(long tenantId, LocalDate bizDate, ChannelReconcileFile channelFile) {
        // 1. 平台侧：当日物流运费费用单（逐运单）
        List<DailyReconcileSourceMapper.WaybillAmountRow> platformRows = sourceMapper.selectWaybillFees(tenantId,
                bizDate);
        Map<String, BigDecimal> platform = new HashMap<>();
        BigDecimal platformTotal = BigDecimal.ZERO;
        for (DailyReconcileSourceMapper.WaybillAmountRow row : platformRows) {
            platform.put(row.waybillNo(), row.amount());
            platformTotal = platformTotal.add(row.amount());
        }
        // 2. 对方侧：承运商账单（可空 → 待接入）
        boolean hasFile = channelFile != null && channelFile.carrierFees() != null;
        if (!hasFile) {
            return save(tenantId, bizDate, DailyReconcileLayer.CARRIER, platformTotal, BigDecimal.ZERO,
                    platformTotal, 0, false, List.of());
        }
        Map<String, BigDecimal> carrier = channelFile.carrierFees();
        BigDecimal carrierTotal = BigDecimal.ZERO;
        long diffCount = 0;
        List<String> details = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : carrier.entrySet()) {
            carrierTotal = carrierTotal.add(entry.getValue());
            BigDecimal ours = platform.get(entry.getKey());
            // 3. 逐单判定：复用 ReconcileDiffRule（≤1 元或 ≤1% 自动平账，超阈值才计入差异）
            ReconcileDiffRule.Result result = ReconcileDiffRule.check(
                    ours == null ? BigDecimal.ZERO : ours, entry.getValue());
            if (result.handleResult() != com.wms.platform.modules.fns.domain.HandleResult.AUTO) {
                diffCount++;
                details.add("运单 " + entry.getKey() + "：" + result.message());
            }
        }
        // 4. 承运商有、平台无（漏计费）也要计入
        for (Map.Entry<String, BigDecimal> entry : platform.entrySet()) {
            if (!carrier.containsKey(entry.getKey())) {
                diffCount++;
                details.add("运单 " + entry.getKey() + "：平台有运费 " + entry.getValue() + " 但承运商账单缺失");
            }
        }
        return save(tenantId, bizDate, DailyReconcileLayer.CARRIER, platformTotal, carrierTotal,
                platformTotal.subtract(carrierTotal), (int) diffCount, true, details);
    }

    /**
     * 库存层：三态与两级账（**复用库存域的不变量校验**）。
     *
     * @param tenantId 租户
     * @param bizDate  业务日期
     * @return 勾稽结果
     */
    private DailyReconciliationVo reconcileInventory(long tenantId, LocalDate bizDate,
                                                        List<String> inventoryViolations) {
        // **复用库存域的能力**（不重复实现校验：重复实现等于两套真相，迟早不一致）
        List<String> details = inventoryViolations;
        long violated = details.size();
        // 库存层无"金额"概念，用违反行数作为差异笔数；金额恒为 0
        return save(tenantId, bizDate, DailyReconcileLayer.INVENTORY, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, (int) violated, true, details);
    }

    /**
     * 落库（幂等：{@code uk_daily_layer} 保证同一天同一层只有一条；已处理的不被覆盖）。
     *
     * @param tenantId       租户
     * @param bizDate        业务日期
     * @param layer          层
     * @param platformAmount 平台侧金额
     * @param channelAmount  对方侧金额
     * @param diffAmount     差额
     * @param diffCount      差异笔数
     * @param hasChannelData 是否有对方侧数据
     * @param details        差异明细
     * @return 勾稽结果
     */
    private DailyReconciliationVo save(long tenantId, LocalDate bizDate, DailyReconcileLayer layer,
                                       BigDecimal platformAmount, BigDecimal channelAmount, BigDecimal diffAmount,
                                       int diffCount, boolean hasChannelData, List<String> details) {
        String status = DailyReconcileRule.judge(layer, hasChannelData, diffAmount, diffCount);
        String detailJson = toDetailJson(status, details);
        FnDailyReconciliationPo po = dailyMapper.selectByDateLayer(tenantId, bizDate, layer.code());
        if (po == null) {
            po = FnDailyReconciliationPo.of(idGenerator.nextId(), tenantId, bizDate, layer.code());
            po.setCreatedBy(TenantContext.userId());
            // 结论经 Po 的语义方法写入（状态只能由状态机改，01 §1.2）
            po.applyResult(platformAmount, channelAmount, diffAmount, diffCount, status, detailJson,
                    TenantContext.userId());
            try {
                dailyMapper.insert(po);
            } catch (DuplicateKeyException ex) {
                // 并发重跑：唯一键兜住，转为覆盖更新
                dailyMapper.updateResult(tenantId, bizDate, layer.code(), platformAmount, channelAmount,
                        diffAmount, diffCount, status, detailJson, TenantContext.userId());
                po = dailyMapper.selectByDateLayer(tenantId, bizDate, layer.code());
            }
        } else {
            int affected = dailyMapper.updateResult(tenantId, bizDate, layer.code(), platformAmount, channelAmount,
                    diffAmount, diffCount, status, detailJson, TenantContext.userId());
            if (affected == 0 && DailyReconcileRule.STATUS_HANDLED.equals(po.getStatus())) {
                // 已处理：不覆盖（人工的结论与理由保留）
                log.info("该层差异已人工处理，本次重跑不覆盖 layer={} bizDate={}", layer.code(), bizDate);
                return toVo(po);
            }
            po = dailyMapper.selectByDateLayer(tenantId, bizDate, layer.code());
        }
        return toVo(po);
    }

    /**
     * 组装差异明细 JSON（手写而非引入 JSON 库：结构固定且只用于展示与排查）。
     *
     * @param status  状态
     * @param details 明细
     * @return JSON 字符串
     */
    private String toDetailJson(String status, List<String> details) {
        StringBuilder sb = new StringBuilder("{\"status\":\"").append(status).append("\"");
        if (!details.isEmpty()) {
            sb.append(",\"items\":[");
            for (int i = 0; i < details.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                // 说明文字里可能有双引号，做最小转义
                sb.append("\"").append(details.get(i).replace("\\", "").replace("\"", "'")).append("\"");
            }
            sb.append("]");
        }
        return sb.append("}").toString();
    }

    /**
     * 记录 → Vo。
     *
     * @param row 记录
     * @return Vo
     */
    private DailyReconciliationVo toVo(FnDailyReconciliationPo row) {
        return new DailyReconciliationVo(row.getId(), row.getBizDate(), row.getLayer(), row.getStatus(),
                row.getPlatformAmount(), row.getChannelAmount(), row.getDiffAmount(),
                row.getDiffCount() == null ? 0 : row.getDiffCount(), row.getDetailJson(), row.getHandledBy());
    }
}
