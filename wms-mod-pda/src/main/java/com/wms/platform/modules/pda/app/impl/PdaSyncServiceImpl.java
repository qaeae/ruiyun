package com.wms.platform.modules.pda.app.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.common.idempotent.IdempotentService;
import com.wms.platform.common.idempotent.RequestHasher;
import com.wms.platform.modules.iv.app.InventoryService;
import com.wms.platform.modules.iv.app.LocationStockView;
import com.wms.platform.modules.iv.app.StockOpCommand;
import com.wms.platform.modules.iv.app.StockView;
import com.wms.platform.modules.md.app.MasterDataQueryService;
import com.wms.platform.modules.pda.api.ro.PdaSyncBatchRo;
import com.wms.platform.modules.pda.api.ro.PdaSyncItemRo;
import com.wms.platform.modules.pda.api.vo.PdaConflictVo;
import com.wms.platform.modules.pda.api.vo.PdaSyncBatchVo;
import com.wms.platform.modules.pda.api.vo.PdaSyncResultVo;
import com.wms.platform.modules.pda.app.PdaSyncService;
import com.wms.platform.modules.pda.domain.ConflictType;
import com.wms.platform.modules.pda.domain.OfflineSyncRule;
import com.wms.platform.modules.pda.domain.PdaAction;
import com.wms.platform.modules.pda.domain.SyncItemStatus;
import com.wms.platform.modules.pda.infrastructure.mapper.PdaConflictMapper;
import com.wms.platform.modules.pda.infrastructure.mapper.SyncBizStatusMapper;
import com.wms.platform.modules.pda.infrastructure.po.BizStatusRow;
import com.wms.platform.modules.pda.infrastructure.po.PdaConflictPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * PDA 离线补传应用服务实现（03 §3.12 API-PDA-01 / API-PDA-03）。
 *
 * <p><b>逐条七步裁决（单条失败绝不影响整批）</b>：
 * <ol>
 *   <li>动作合法性 → 非法动作直接拒绝，**不进幂等表**（避免脏键占位后永久 replay）；</li>
 *   <li>幂等裁决（{@code pf_idempotent} 第一层）→ `REPLAY` = {@code duplicated}；</li>
 *   <li>查服务端单据状态（"状态已流转"判据）；</li>
 *   <li>解析内部 SKU / 库位（"错扫"判据）；</li>
 *   <li>纯规则判定（{@link OfflineSyncRule}，与实时上传共用同一套判据）；</li>
 *   <li>冲突 → 落 {@code pf_pda_conflict} 人工待办，**绝不覆盖服务端状态**；</li>
 *   <li>受理 → 交库存域执行（{@link InventoryService}），唯一键冲突 → `duplicated`。</li>
 * </ol>
 *
 * <p><b>2026-10-14（W3）跨域缝隙已清理</b>：此前本类自带 {@code SyncStockMapper} /
 * {@code SyncMovementMapper} / {@code SyncMovementPo} 直接读写 {@code iv_*} 表（W1 时 {@code iv} 域尚未落地），
 * 属已登记的技术债。现在：
 * <ul>
 *   <li>库存动作（CAS + 流水幂等 + 事务）**全部由库存域内部完成**，PDA 侧不再有第二套库存 SQL；</li>
 *   <li>编码解析改调 {@link MasterDataQueryService}（`md_sku`/`md_location`）——
 *       "扫了不存在的库位"因此有了正确依据（此前的近似判定会把"新库位首次上架"误判成错扫）；</li>
 *   <li>仅存的跨域依赖是 {@link SyncBizStatusMapper}（**只读** DO/ASN 状态），
 *       待 W7 出库核心落地 {@code outbound.app}/{@code inbound.app} 后替换。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PdaSyncServiceImpl implements PdaSyncService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(PdaSyncServiceImpl.class);

    /** 幂等业务类型。 */
    private static final String BIZ_TYPE = "PDA_SYNC";

    /** 默认冲突列表条数。 */
    private static final int DEFAULT_CONFLICT_LIMIT = 200;

    /** 单据状态查询（临时跨域只读，W7 移除）。 */
    private final SyncBizStatusMapper bizStatusMapper;

    /** 库存域服务（库存动作的唯一入口）。 */
    private final InventoryService inventoryService;

    /** 主数据解析（业务编码 → 内部 ID 的唯一入口）。 */
    private final MasterDataQueryService masterData;

    /** 冲突任务 Mapper。 */
    private final PdaConflictMapper conflictMapper;

    /** 幂等裁决服务。 */
    private final IdempotentService idempotentService;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** JSON。 */
    private final ObjectMapper objectMapper;

    @Override
    public PdaSyncBatchVo syncBatch(PdaSyncBatchRo ro) {
        // 1. 逐条裁决
        List<PdaSyncResultVo> results = new ArrayList<>(ro.items().size());
        for (PdaSyncItemRo item : ro.items()) {
            results.add(syncOne(ro.deviceCode(), ro.operatorId(), item));
        }
        // 2. 汇总（计数由 results 推得，避免汇总口径与逐条结果不一致）
        return PdaSyncBatchVo.of(ro.batchSeq(), results);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PdaConflictVo> listConflicts(int limit) {
        // 1. 只返回"待人工处理"（已处理的归档不打扰现场）
        List<PdaConflictPo> list = conflictMapper.selectPending(TenantContext.tenantId(),
                limit > 0 ? limit : DEFAULT_CONFLICT_LIMIT);
        // 2. 转 Vo
        List<PdaConflictVo> vos = new ArrayList<>(list.size());
        for (PdaConflictPo po : list) {
            vos.add(toConflictVo(po));
        }
        return vos;
    }

    /**
     * 裁决并处理一条补传。
     *
     * @param deviceCode 设备编号（由请求头校验后传入）
     * @param operatorId 操作人
     * @param item       补传项
     * @return 逐条结果
     */
    private PdaSyncResultVo syncOne(String deviceCode, Long operatorId, PdaSyncItemRo item) {
        // 1. 动作合法性：非法动作直接拒绝，不入幂等表（脏键占位会导致该键后续永久 replay）
        PdaAction action;
        try {
            action = PdaAction.of(item.action());
        } catch (BizException ex) {
            return result(item, SyncItemStatus.REJECTED, null, ex.getMessage());
        }

        // 2. 幂等裁决（第一层防线）
        String idemKey = OfflineSyncRule.idemKey(deviceCode, item.taskId(), action, item.seq());
        IdempotentService.Decision decision = idempotentService.begin(idemKey, BIZ_TYPE, RequestHasher.hash(item));
        if (decision == IdempotentService.Decision.REPLAY) {
            return result(item, SyncItemStatus.DUPLICATED, null, "幂等重复，已忽略（服务端已受理过该条）");
        }
        if (decision == IdempotentService.Decision.MISMATCH) {
            // 同键不同内容：客户端 bug 或本地队列串号，必须人工确认，不能静默按第一次处理
            return result(item, SyncItemStatus.REJECTED, null,
                    "同一幂等键提交了不同内容（疑似客户端问题），请重扫并上报");
        }
        if (decision == IdempotentService.Decision.IN_PROGRESS) {
            return result(item, SyncItemStatus.REJECTED, null, "该条目正在处理中，请稍后重试");
        }

        // 3. 幂等表已过期（>7 天）但库存动作其实执行过 → 必须回执 duplicated，而不是再扣一次
        if (inventoryService.hasApplied(idemKey)) {
            log.info("幂等表已过期但库存流水存在，判定为重复补传 idemKey={}", idemKey);
            return result(item, SyncItemStatus.DUPLICATED, null, "幂等重复（库存流水已存在）");
        }

        // 4. 查服务端单据状态
        BizStatusRow biz = "ASN".equals(action.getBizType())
                ? bizStatusMapper.selectAsn(TenantContext.tenantId(), item.bizNo())
                : bizStatusMapper.selectDo(TenantContext.tenantId(), item.bizNo());

        // 5. 解析内部 SKU / 库位（主数据唯一入口；解析不出即错扫）
        Long skuId = biz == null || item.skuCode() == null ? null
                : masterData.findSkuId(biz.getMerchantId(), item.skuCode());
        Long locationId = biz == null || item.locationCode() == null ? null
                : masterData.findLocationId(biz.getWarehouseId(), item.locationCode());
        // 5.1 装车（LOAD）按单确认、报文不带库位（03 §七），故不要求库位编码（库位由服务端推导）
        boolean codeKnown = biz != null && skuId != null && (action == PdaAction.LOAD || locationId != null);

        // 6. 库存是否足够（各动作口径不同，见 stockEnoughFor）
        boolean stockEnough = codeKnown && stockEnoughFor(action, biz, skuId, locationId, item);

        // 7. 纯规则判定
        OfflineSyncRule.Verdict verdict =
                OfflineSyncRule.judge(action, biz == null ? null : biz.getStatus(), codeKnown, stockEnough);

        // 8. 拒绝：保留本地队列（不入队即等于现场作业真丢了）
        if (verdict.status() == SyncItemStatus.REJECTED) {
            idempotentService.fail(idemKey);
            log.info("补传被拒 idemKey={} reason={}", idemKey, verdict.message());
            return result(item, SyncItemStatus.REJECTED, biz == null ? null : biz.getStatus(), verdict.message());
        }

        // 9. 冲突：落人工待办 + 不覆盖服务端状态
        if (verdict.status() == SyncItemStatus.CONFLICT) {
            saveConflict(deviceCode, item, action, idemKey, biz, verdict);
            idempotentService.fail(idemKey);
            log.warn("补传冲突 idemKey={} type={} serverStatus={}", idemKey, verdict.conflictType(),
                    biz == null ? null : biz.getStatus());
            return result(item, SyncItemStatus.CONFLICT, biz == null ? null : biz.getStatus(), verdict.message());
        }

        // 10. 受理：交库存域执行
        return applyToInventory(deviceCode, operatorId, item, action, idemKey, biz, skuId, locationId);
    }

    /**
     * 调库存域执行动作，并把库存域结果翻译成 PDA 逐条结果。
     *
     * @param deviceCode 设备
     * @param operatorId 操作人
     * @param item       补传项
     * @param action     动作
     * @param idemKey    幂等键（同源作为库存流水幂等键）
     * @param biz        单据
     * @param skuId      商品 ID
     * @param locationId 库位 ID（LOAD 为 {@code null}，由服务端推导）
     * @return 逐条结果
     */
    private PdaSyncResultVo applyToInventory(String deviceCode, Long operatorId, PdaSyncItemRo item, PdaAction action,
                                             String idemKey, BizStatusRow biz, Long skuId, Long locationId) {
        // 1. 装车出库必须能定位库位，否则 SKU 账减了、库位账没减（01 §4 不变量 2 日终必报差异）
        Long shipLocationId = locationId;
        if (action == PdaAction.LOAD && shipLocationId == null) {
            LocationChoice choice = resolveShipLocation(biz.getWarehouseId(), skuId, item.qty());
            if (choice.locationId() == null) {
                OfflineSyncRule.Verdict stockConflictSource =
                        OfflineSyncRule.judge(action, biz.getStatus(), true, false);
                OfflineSyncRule.Verdict conflict = new OfflineSyncRule.Verdict(stockConflictSource.status(),
                        stockConflictSource.conflictType(), stockConflictSource.targetStatus(), choice.message());
                saveConflict(deviceCode, item, action, idemKey, biz, conflict);
                idempotentService.fail(idemKey);
                return result(item, SyncItemStatus.CONFLICT, biz.getStatus(), choice.message());
            }
            shipLocationId = choice.locationId();
        }

        // 2. 组装库存命令（幂等键与补传同源）
        StockOpCommand command = new StockOpCommand(biz.getWarehouseId(), biz.getMerchantId(), skuId, item.qty(),
                action.getBizType(), biz.getId(), item.bizNo(), idemKey, shipLocationId, operatorId, deviceCode);

        // 3. 按动作调库存域
        try {
            switch (action) {
                case PICK -> inventoryService.pick(command);
                case LOAD -> inventoryService.ship(command);
                case PUTAWAY -> inventoryService.receive(command);
                default -> throw new BizException(ErrorCode.SYSTEM_ERROR, "未实现的补传动作：" + action);
            }
        } catch (DuplicateKeyException ex) {
            // 库存流水唯一键命中：事务已回滚，库存不会二次扣减 → 回执幂等重复
            log.info("库存流水撞幂等唯一键，判定为重复补传 idemKey={}", idemKey);
            return result(item, SyncItemStatus.DUPLICATED, biz.getStatus(), "幂等重复（库存流水已存在）");
        } catch (BizException ex) {
            // 4. 语义分流：库存/库位类失败 → 冲突（转人工）；参数类 → 拒绝（重扫）
            if (isStockConflict(ex)) {
                OfflineSyncRule.Verdict source = OfflineSyncRule.judge(action, biz.getStatus(), true, false);
                OfflineSyncRule.Verdict conflict = new OfflineSyncRule.Verdict(source.status(), source.conflictType(),
                        source.targetStatus(), ex.getMessage());
                saveConflict(deviceCode, item, action, idemKey, biz, conflict);
                idempotentService.fail(idemKey);
                log.warn("库存域拒绝补传（转冲突）idemKey={} reason={}", idemKey, ex.getMessage());
                return result(item, SyncItemStatus.CONFLICT, biz.getStatus(), ex.getMessage());
            }
            idempotentService.fail(idemKey);
            return result(item, SyncItemStatus.REJECTED, biz.getStatus(), ex.getMessage());
        }

        // 5. 成功：缓存响应，后续重放直接返回 duplicated
        PdaSyncResultVo vo = result(item, SyncItemStatus.ACCEPTED, biz.getStatus(),
                "受理成功（目标状态：" + action.getTargetStatus() + "）");
        idempotentService.complete(idemKey, toJson(vo));
        return vo;
    }

    /**
     * 推导装车出库的库位。
     *
     * <p><b>为什么需要推导</b>：{@code 03} §七 的 LOAD 报文按单确认、**不带库位**，
     * 而 {@code 01} §4 不变量 2 要求"库位明细合计 = SKU 实物"，出库必须落到具体库位。
     *
     * <p>推导规则（W7 出库核心落地后改为取出库单的分配记录）：
     * <ol>
     *   <li>该 SKU 在该仓库的库位账中，实物 ≥ 需求量的**恰好一条** → 用它；</li>
     *   <li>0 条 → 冲突（库位实物不足）；</li>
     *   <li>多条 → 冲突（无法唯一定位，转人工）——**不猜**：猜错会把 A 库位的账扣掉，
     *       现场盘点时 B 库位会凭空多出实物。</li>
     * </ol>
     *
     * @param warehouseId 仓库
     * @param skuId       商品
     * @param qty         数量（可空视为 0）
     * @return 库位选择结果
     */
    private LocationChoice resolveShipLocation(long warehouseId, long skuId, BigDecimal qty) {
        // 1. 取该 SKU 的库位账
        List<LocationStockView> locations = inventoryService.listLocations(warehouseId, skuId);
        // 2. 筛出实物足够的库位
        BigDecimal need = qty == null ? BigDecimal.ZERO : qty;
        List<LocationStockView> candidates = new ArrayList<>();
        for (LocationStockView location : locations) {
            if (location.physicalQty().compareTo(need) >= 0) {
                candidates.add(location);
            }
        }
        // 3. 唯一定位
        if (candidates.size() == 1) {
            return new LocationChoice(candidates.get(0).locationId(), null);
        }
        if (candidates.isEmpty()) {
            return new LocationChoice(null, "库位实物不足，无法装车出库 " + need + "（转人工核对）");
        }
        return new LocationChoice(null, "该商品在 " + candidates.size()
                + " 个库位都有实物，无法唯一定位出库库位（转人工指定后再出库）");
    }

    /**
     * 库存是否足够（各动作口径不同）。
     *
     * @param action     动作
     * @param biz        单据
     * @param skuId      商品 ID
     * @param locationId 库位 ID（LOAD 为 {@code null}）
     * @param item       补传项
     * @return 足够返回 {@code true}
     */
    private boolean stockEnoughFor(PdaAction action, BizStatusRow biz, Long skuId, Long locationId,
                                   PdaSyncItemRo item) {
        BigDecimal qty = item.qty() == null ? BigDecimal.ZERO : item.qty();
        switch (action) {
            case PICK -> {
                // 拣货：看该库位"已分配待拣"量（派发时已从可用量转分配）
                for (LocationStockView location : inventoryService.listLocations(biz.getWarehouseId(), skuId)) {
                    if (location.locationId() == locationId) {
                        return location.allocatedQty().compareTo(qty) >= 0;
                    }
                }
                return false;
            }
            case LOAD -> {
                // 出库：预占是出库依据，实物是账实依据，两者都要够
                StockView state = inventoryService.querySku(biz.getWarehouseId(), biz.getMerchantId(), skuId);
                return state.physicalQty().compareTo(qty) >= 0 && state.reservedQty().compareTo(qty) >= 0;
            }
            default -> {
                // 上架只增加库存，不存在"不足"
                return true;
            }
        }
    }

    /**
     * 是否为"库存/库位类"失败（应转冲突，而不是让现场重扫）。
     *
     * @param ex 异常
     * @return 是则返回 {@code true}
     */
    private boolean isStockConflict(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        return code == ErrorCode.AVAILABLE_STOCK_INSUFFICIENT
                || code == ErrorCode.STOCK_OCCUPIED
                || code == ErrorCode.LOCATION_NOT_PICKABLE
                || code == ErrorCode.INVARIANT_VIOLATED;
    }

    /**
     * 库位选择结果。
     *
     * @param locationId 库位 ID（推导失败为 {@code null}）
     * @param message    失败原因（成功为 {@code null}）
     */
    private record LocationChoice(Long locationId, String message) {
    }

    /**
     * 落"待人工处理"冲突任务。
     *
     * @param deviceCode 设备编号
     * @param item       补传项
     * @param action     动作
     * @param idemKey    幂等键
     * @param biz        单据（可空）
     * @param verdict    判定结论
     */
    private void saveConflict(String deviceCode, PdaSyncItemRo item, PdaAction action, String idemKey,
                              BizStatusRow biz, OfflineSyncRule.Verdict verdict) {
        PdaConflictPo po = PdaConflictPo.of(idGenerator.nextId(), TenantContext.tenantId(), idemKey, deviceCode,
                item.taskId(), action.name(), item.seq(), item.bizNo(),
                verdict.conflictType() == null ? ConflictType.STATUS_CHANGED.getLabel()
                        : verdict.conflictType().getLabel(),
                biz == null ? null : biz.getStatus(), verdict.targetStatus(), toJson(item), verdict.message(),
                item.scannedAt());
        try {
            conflictMapper.insert(po);
        } catch (DuplicateKeyException ex) {
            // uk_pda_idem：同一补传只留一条待办（冗余补传是常态，人工待办不能被刷屏）
            log.debug("冲突任务已存在，忽略重复写入 idemKey={}", idemKey);
        }
    }

    /**
     * 组装逐条结果。
     *
     * @param item         补传项
     * @param status       结果
     * @param serverStatus 服务端状态
     * @param message      说明
     * @return 结果 Vo
     */
    private PdaSyncResultVo result(PdaSyncItemRo item, SyncItemStatus status, String serverStatus, String message) {
        return PdaSyncResultVo.of(item.taskId(), item.seq(), status.getCode(), serverStatus, message);
    }

    /**
     * 冲突任务 → Vo。
     *
     * @param po 冲突任务
     * @return Vo
     */
    private PdaConflictVo toConflictVo(PdaConflictPo po) {
        PdaConflictVo vo = new PdaConflictVo();
        vo.setIdemKey(po.getIdemKey());
        vo.setDeviceCode(po.getDeviceCode());
        vo.setTaskId(po.getTaskId());
        vo.setAction(po.getAction());
        vo.setSeq(po.getSeq() == null ? 0 : po.getSeq());
        vo.setBizNo(po.getBizNo());
        vo.setConflictType(po.getConflictType());
        vo.setServerStatus(po.getServerStatus());
        vo.setTargetStatus(po.getTargetStatus());
        vo.setMessage(po.getMessage());
        vo.setScannedAt(po.getScannedAt());
        vo.setOccurredAt(po.getOccurredAt());
        vo.setStatus(po.getStatus());
        return vo;
    }

    /**
     * 序列化（缓存响应 / 存原始报文）。
     *
     * @param value 对象
     * @return JSON
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "补传报文序列化失败", ex);
        }
    }
}
