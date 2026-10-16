package com.wms.platform.modules.logistics.app.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.id.SnowflakeIdGenerator;
import com.wms.platform.modules.logistics.app.ShipmentService;
import com.wms.platform.modules.logistics.domain.ShipmentNoRule;
import com.wms.platform.modules.logistics.infrastructure.mapper.ShipmentMapper;
import com.wms.platform.modules.logistics.infrastructure.po.ShipmentPo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 装运单应用服务实现（本轮最小面：建单联动生成"待取号"装运单）。
 *
 * <p>幂等靠"先查 (doId, carrierCode)"——出库建单本身有幂等键与唯一键兜底，走到这里时
 * 已经是"第一次建单"，因此这一步是**防御性**的：防止同一 DO 因重放而生成两张装运单，
 * 那会让后续取号与费用回传出现"两单抢一个 DO"的错乱。
 */
@Service
@RequiredArgsConstructor
public class ShipmentServiceImpl implements ShipmentService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ShipmentServiceImpl.class);

    /** 装运单。 */
    private final ShipmentMapper shipmentMapper;

    /** 雪花 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 出库建单联动生成装运单。
     *
     * @param command 建单命令
     * @return 装运单视图
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShipmentView createForOutbound(CreateShipmentCommand command) {
        // 1. 承运商必填：没有承运商就取不到号、打不了面单，属于调用方漏传（而不是"稍后再补"）
        if (command.carrierCode() == null || command.carrierCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "生成装运单必须指定承运商（carrierCode）");
        }
        long tenantId = TenantContext.tenantId();
        // 2. 幂等：同一 DO + 承运商已存在装运单 → 直接复用，不重复生成
        ShipmentPo exist = shipmentMapper.selectByDo(tenantId, command.doId(), command.carrierCode());
        if (exist != null) {
            log.info("装运单幂等命中 doNo={} carrierCode={} shpNo={}",
                    command.doNo(), command.carrierCode(), exist.getShpNo());
            return toView(exist);
        }
        // 3. 生成装运单（状态"待取号"：取号与面单打印属 M8，届时只更新这张单，不改出库链路）
        long id = idGenerator.nextId();
        ShipmentPo po = ShipmentPo.awaitingWaybill(id, tenantId, ShipmentNoRule.shpNo(id), command.doId(),
                command.doNo(), command.warehouseId(), command.merchantId(), command.carrierCode(),
                command.carrierName(), command.estimatedFee(), command.operatorId());
        shipmentMapper.insert(po);
        log.info("生成装运单成功 doNo={} shpNo={} carrierCode={}",
                command.doNo(), po.getShpNo(), command.carrierCode());
        return toView(po);
    }

    /**
     * 查某出库单的装运单。
     *
     * @param doNo 出库单号
     * @return 装运单视图；不存在返回 {@code null}
     */
    @Override
    public ShipmentView findByDoNo(String doNo) {
        ShipmentPo po = shipmentMapper.selectByDoNo(TenantContext.tenantId(), doNo);
        return po == null ? null : toView(po);
    }

    /**
     * 绑定/校验运单号（打包环节）。
     *
     * @param doNo       出库单号
     * @param waybillNo  运单号
     * @param operatorId 操作人
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindWaybill(String doNo, String waybillNo, Long operatorId) {
        long tenantId = TenantContext.tenantId();
        // 1. 装运单必须存在：面单绑定的载体是装运单，没有它就没有"货与单绑定"这件事
        ShipmentPo po = shipmentMapper.selectByDoNo(tenantId, doNo);
        if (po == null) {
            throw new BizException(ErrorCode.LABEL_NOT_MATCH_ORDER,
                    "该出库单没有装运单，无法绑定面单（doNo=" + doNo + "）");
        }
        // 2. 已作废的面单不能再用于任何 DO（否则等于用废单发货）
        if (ShipmentPo.STATUS_VOIDED.equals(po.getStatus())) {
            throw new BizException(ErrorCode.LABEL_NOT_MATCH_ORDER,
                    "该装运单已作废，面单不可再用（shpNo=" + po.getShpNo() + "）");
        }
        // 3. 已绑定：必须一致（不一致就是扫错面单 → 20004）
        if (po.getWaybillNo() != null && !po.getWaybillNo().isBlank()) {
            if (!po.getWaybillNo().equals(waybillNo)) {
                throw new BizException(ErrorCode.LABEL_NOT_MATCH_ORDER,
                        "面单与单据不匹配：本单运单号为 " + po.getWaybillNo() + "，扫描到 " + waybillNo);
            }
            log.info("面单绑定：已绑定同一运单号（幂等）shpNo={} waybillNo={}", po.getShpNo(), waybillNo);
            return;
        }
        // 4. 首次绑定（uk_waybill 会挡住"一号多单"，翻译成 20004 而不是 19999）
        try {
            shipmentMapper.bindWaybill(tenantId, po.getShpNo(), waybillNo, operatorId);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BizException(ErrorCode.LABEL_NOT_MATCH_ORDER,
                    "该运单号已被其它出库单使用：" + waybillNo);
        }
        log.info("面单绑定成功 doNo={} shpNo={} waybillNo={}", doNo, po.getShpNo(), waybillNo);
    }

    /**
     * 作废装运单（出库取消/拦截联动）。
     *
     * @param doNo       出库单号
     * @param reason     作废原因
     * @param operatorId 操作人
     * @return 本次是否真的作废
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelVoid(String doNo, String reason, Long operatorId) {
        long tenantId = TenantContext.tenantId();
        // 1. 没装运单（例如建单联动未生成）→ 按"无需作废"返回，不能因为缺附属单让取消失败
        ShipmentPo po = shipmentMapper.selectByDoNo(tenantId, doNo);
        if (po == null) {
            log.info("作废装运单：该出库单无装运单（按已处理对待）doNo={}", doNo);
            return false;
        }
        // 2. 已揽收/运输中/已签收：承运商侧已在运输，本地标废只会让面单与实物彻底对不上
        if (ShipmentPo.STATUS_VOIDED.equals(po.getStatus())) {
            log.info("作废装运单：已作废（幂等）shpNo={}", po.getShpNo());
            return false;
        }
        if (ShipmentPo.STATUS_NOT_VOIDABLE.contains(po.getStatus())) {
            throw new BizException(ErrorCode.LABEL_NOT_VOIDABLE,
                    "面单不可作废（当前状态：" + po.getStatus() + "，已进入承运流程）：" + po.getShpNo());
        }
        // 3. 条件更新作废（并发下只有一个能改成"已作废"）
        int affected = shipmentMapper.markVoided(tenantId, po.getShpNo(), reason == null ? "出库取消" : reason,
                LocalDateTime.now(), operatorId);
        log.info("作废装运单完成 shpNo={} affected={} 原因={}", po.getShpNo(), affected, reason);
        return affected > 0;
    }

    /**
     * Po → 视图（跨域只交换必要字段，禁止把 Po 暴露给调用方——那是给对方域的 Mapper 开后门）。
     *
     * @param po 装运单
     * @return 视图
     */
    private ShipmentView toView(ShipmentPo po) {
        return new ShipmentView(po.getId(), po.getShpNo(), po.getDoNo(), po.getStatus(), po.getWaybillNo());
    }
}
