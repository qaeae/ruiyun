package com.wms.platform.modules.iv.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.iv.api.ro.StocktakeCreateRo;
import com.wms.platform.modules.iv.api.ro.StocktakeRecordRo;
import com.wms.platform.modules.iv.api.vo.StocktakeItemVo;
import com.wms.platform.modules.iv.api.vo.StocktakeVo;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.iv.app.StocktakeService;
import com.wms.platform.modules.iv.domain.StocktakeRule;
import com.wms.platform.modules.iv.infrastructure.mapper.IvLocationMapper;
import com.wms.platform.modules.iv.infrastructure.mapper.IvStocktakeItemMapper;
import com.wms.platform.modules.iv.infrastructure.mapper.IvStocktakeMapper;
import com.wms.platform.modules.iv.infrastructure.po.IvLocationPo;
import com.wms.platform.modules.iv.infrastructure.po.IvStocktakeItemPo;
import com.wms.platform.modules.iv.infrastructure.po.IvStocktakePo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点服务实现。
 *
 * <p>规范落地：B1（Wrapper 只在 Mapper）、B4（Po 工厂）、B5（带码异常）、B7（编号步骤注释）、B9（构造注入）。
 */
@Service
@RequiredArgsConstructor
public class StocktakeServiceImpl implements StocktakeService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(StocktakeServiceImpl.class);

    /** 盘点单 Mapper。 */
    private final IvStocktakeMapper stocktakeMapper;

    /** 盘点明细 Mapper。 */
    private final IvStocktakeItemMapper itemMapper;

    /** 库位账 Mapper（建单时取账面快照）。 */
    private final IvLocationMapper locationMapper;

    /** 库存服务（审批后按差异调整）。 */
    private final InventoryService inventoryService;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(StocktakeCreateRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 生成单号（PT+日期+当日序号；序号用雪花低位，避免额外计数表）
        String stocktakeNo = StocktakeRule.stocktakeNo(idGenerator.nextId());
        // 2. 建单（状态由 Po 工厂内置「待录入」）
        IvStocktakePo head = IvStocktakePo.of(idGenerator.nextId(), tenant, stocktakeNo, ro.warehouseId(),
                ro.zoneId(), ro.scopeType() == null ? "库区" : ro.scopeType(), TenantContext.userId());
        head.setRemark(ro.remark());
        head.setUpdatedBy(TenantContext.userId());
        stocktakeMapper.insert(head);
        // 3. 生成明细：**账面快照在建单时固定**（差异基准）
        List<IvLocationPo> snapshots = locationMapper.selectStockForStocktake(tenant, ro.warehouseId(), ro.zoneId());
        int itemCount = 0;
        for (IvLocationPo location : snapshots) {
            IvStocktakeItemPo item = IvStocktakeItemPo.of(idGenerator.nextId(), tenant, head.getId(),
                    location.getMerchantId(), location.getSkuId(), location.getLocationId(),
                    location.getPhysicalQty());
            item.setCreatedBy(TenantContext.userId());
            itemMapper.insert(item);
            itemCount++;
        }
        // 4. 回写明细条数
        stocktakeMapper.updateItemCount(tenant, stocktakeNo, itemCount, LocalDateTime.now(), TenantContext.userId());
        log.info("创建盘点单 no={} 明细条数={}（账面快照已固定）", stocktakeNo, itemCount);
        return stocktakeNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int record(String stocktakeNo, StocktakeRecordRo ro) {
        long tenant = TenantContext.tenantId();
        // 1. 单头必须存在且处于「待录入」
        IvStocktakePo head = requireStocktake(tenant, stocktakeNo);
        StocktakeRule.assertRecordable(head.getStatus());
        // 2. 逐条录入：差异 = 实盘 − **账面快照**
        int recorded = 0;
        for (StocktakeRecordRo.Item item : ro.items()) {
            IvStocktakeItemPo exists = itemMapper.selectOneItem(tenant, head.getId(), item.skuId(),
                    item.locationId());
            if (exists == null) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "该商品不在本次盘点范围内：skuId=" + item.skuId() + " locationId=" + item.locationId()
                                + "（盘点范围以外的库存不能通过盘点单调整）");
            }
            BigDecimal diff = StocktakeRule.diff(exists.getBookQty(), item.actualQty());
            int affected = itemMapper.record(tenant, head.getId(), item.skuId(), item.locationId(), item.actualQty(),
                    diff, item.remark(), TenantContext.userId());
            if (affected == 0) {
                // 已录入过或状态被改：明确拒绝而不是静默重写（重复录入通常是"两个人同时盘"）
                throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                        "明细已录入过或不可修改：skuId=" + item.skuId() + " locationId=" + item.locationId());
            }
            recorded++;
        }
        log.info("盘点录入 no={} 本次条数={}", stocktakeNo, recorded);
        return recorded;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submit(String stocktakeNo) {
        long tenant = TenantContext.tenantId();
        // 1. 状态与明细校验
        IvStocktakePo head = requireStocktake(tenant, stocktakeNo);
        long itemCount = itemMapper.countItems(tenant, head.getId());
        StocktakeRule.assertSubmittable(head.getStatus(), itemCount);
        // 2. 统计差异条数（审批人据此判断风险与工作量）
        int diffCount = (int) itemMapper.countDiff(tenant, head.getId());
        // 3. 条件更新（仅「待录入」→「待审批」；影响 0 行说明有人并发提交了）
        int affected = stocktakeMapper.submit(tenant, stocktakeNo, StocktakeRule.STATUS_PENDING, diffCount,
                TenantContext.userId());
        if (affected == 0) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION, "盘点单状态已变化，请刷新后重试");
        }
        log.info("盘点提交审批 no={} 差异条数={}/{}", stocktakeNo, diffCount, itemCount);
        return diffCount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int approve(String stocktakeNo, long approverId) {
        long tenant = TenantContext.tenantId();
        // 1. 状态 + **双人复核**（创建人不得自审）
        IvStocktakePo head = requireStocktake(tenant, stocktakeNo);
        StocktakeRule.assertApprovable(head.getStatus(), head.getCreatedBy(), approverId);
        // 2. 先落状态（条件更新，避免并发双审批各自生成一套流水）
        int affected = stocktakeMapper.approve(tenant, stocktakeNo, StocktakeRule.STATUS_APPROVED, approverId);
        if (affected == 0) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION, "盘点单已被审批或状态已变化");
        }
        // 3. 对每条有差异的明细生成 ADJUST 流水（幂等键含单号+SKU+库位，重复执行不会二次调整）
        List<IvStocktakeItemPo> items = itemMapper.selectByStocktake(tenant, head.getId());
        int adjusted = 0;
        for (IvStocktakeItemPo item : items) {
            if (!StocktakeRule.needsAdjust(item.getDiffQty(), item.getItemStatus())) {
                continue;
            }
            String idemKey = "STOCKTAKE:" + stocktakeNo + ":" + item.getSkuId() + ":" + item.getLocationId();
            StockOpCommand command = new StockOpCommand(head.getWarehouseId(), item.getMerchantId(),
                    item.getSkuId(), item.getDiffQty(), "STOCKTAKE", head.getId(), stocktakeNo, idemKey,
                    item.getLocationId(), approverId, null);
            try {
                inventoryService.adjust(command);
            } catch (DuplicateKeyException ex) {
                // 该条已调整过（重复审批/重试）：按幂等成功处理
                log.info("盘点调整命中幂等键，跳过 no={} skuId={}", stocktakeNo, item.getSkuId());
            }
            itemMapper.markAdjusted(tenant, head.getId(), item.getSkuId(), item.getLocationId(), approverId);
            adjusted++;
        }
        log.warn("盘点审批通过 no={} 审批人={} 调整条数={}（账实差异已按此合法化）", stocktakeNo, approverId, adjusted);
        return adjusted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(String stocktakeNo, String reason, long operatorId) {
        long tenant = TenantContext.tenantId();
        // 1. 状态校验
        IvStocktakePo head = requireStocktake(tenant, stocktakeNo);
        StocktakeRule.assertRejectable(head.getStatus());
        // 2. 驳回必须说明原因（否则现场不知道要改什么，只能重新盘一遍）
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "驳回必须填写原因");
        }
        // 3. 条件更新
        int affected = stocktakeMapper.reject(tenant, stocktakeNo, StocktakeRule.STATUS_REJECTED, reason, operatorId);
        if (affected == 0) {
            throw new BizException(ErrorCode.ILLEGAL_STATUS_TRANSITION, "盘点单状态已变化，请刷新后重试");
        }
        log.info("盘点驳回 no={} 原因={}", stocktakeNo, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StocktakeVo> list(Long warehouseId, String status) {
        long tenant = TenantContext.tenantId();
        List<IvStocktakePo> heads = stocktakeMapper.selectListBy(tenant, warehouseId, status);
        List<StocktakeVo> vos = new ArrayList<>(heads.size());
        for (IvStocktakePo head : heads) {
            vos.add(toVo(head, null));
        }
        return vos;
    }

    @Override
    @Transactional(readOnly = true)
    public StocktakeVo detail(String stocktakeNo) {
        long tenant = TenantContext.tenantId();
        IvStocktakePo head = requireStocktake(tenant, stocktakeNo);
        return toVo(head, itemMapper.selectByStocktake(tenant, head.getId()));
    }

    /**
     * 取盘点单（不存在则报错）。
     *
     * @param tenantId    租户
     * @param stocktakeNo 单号
     * @return 盘点单
     */
    private IvStocktakePo requireStocktake(long tenantId, String stocktakeNo) {
        IvStocktakePo head = stocktakeMapper.selectByNo(tenantId, stocktakeNo);
        if (head == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "盘点单不存在：" + stocktakeNo);
        }
        return head;
    }

    /**
     * 盘点单 → Vo。
     *
     * @param head  单头
     * @param items 明细（可空）
     * @return Vo
     */
    private StocktakeVo toVo(IvStocktakePo head, List<IvStocktakeItemPo> items) {
        List<StocktakeItemVo> itemVos = null;
        if (items != null) {
            itemVos = new ArrayList<>(items.size());
            for (IvStocktakeItemPo item : items) {
                itemVos.add(new StocktakeItemVo(item.getSkuId(), item.getLocationId(), item.getBookQty(),
                        item.getActualQty(), item.getDiffQty(), item.getItemStatus(), item.getRemark()));
            }
        }
        return new StocktakeVo(head.getStocktakeNo(), head.getWarehouseId(), head.getZoneId(),
                head.getScopeType(), head.getStatus(), head.getItemCount() == null ? 0 : head.getItemCount(),
                head.getDiffCount() == null ? 0 : head.getDiffCount(), head.getCreatedBy(), head.getApprovedBy(),
                head.getReason(), head.getRemark(), itemVos);
    }
}
