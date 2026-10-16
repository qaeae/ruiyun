package com.wms.platform.modules.outbound.app;

import com.wms.platform.modules.outbound.api.ro.CancelOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.CreateOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.DispatchOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.HandoverOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.HoldOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.LoadOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.MeasureOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PackOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PageOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PartialShipOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.PickCompleteOutOrderRo;
import com.wms.platform.modules.outbound.api.ro.ShortageOutOrderRo;
import com.wms.platform.modules.outbound.api.vo.OutOrderDetailVo;
import com.wms.platform.modules.outbound.api.vo.OutOrderPageVo;
import com.wms.platform.modules.outbound.api.vo.OutStatusLogVo;
import com.wms.platform.modules.outbound.api.vo.PartialShipVo;

import java.util.List;

/**
 * 出库单应用服务（{@code 03} API-OUT-01 ~ API-OUT-13）。
 *
 * <p><b>事务边界在实现层</b>：每个写方法一个本地事务，覆盖"状态迁移 + 库存动作 + 资金冻结 + 状态流水"。
 * 这些动作必须同生共死——例如"库存预占成功但冻结失败"若各自提交，就会出现
 * "货被占住、钱没冻住"的中间态，且没有任何任务能自动修复它。
 *
 * <p><b>幂等</b>：入口层由 {@code IdempotencyInterceptor} 校验 {@code Idempotency-Key} 存在；
 * 服务层按"动作前缀 + 幂等键"落 {@code pf_idempotent} 做重放，并按状态机的条件更新 +
 * {@code pf_biz_status_log.uk_status_idem} 做业务级兜底。
 *
 * <p><b>越界依赖只有两处</b>（都经端口，见 {@code app.remote}）：费用冻结、装运单。
 * 库存与主数据走对方域的 {@code app} 服务接口（同进程），不直连对方的 Mapper。
 */
public interface OutboundOrderService {

    /**
     * 创建出库单（含预占、运费冻结、生成装运单）。
     *
     * @param ro             入参
     * @param idemKeyHeader  幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo create(CreateOutOrderRo ro, String idemKeyHeader);

    /**
     * 派发拣货任务（待接单 → 拣货中，库位分配）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo dispatch(String doNo, DispatchOutOrderRo ro, String idemKeyHeader);

    /**
     * 拣货完成（拣货中 → 已复核）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo pickComplete(String doNo, PickCompleteOutOrderRo ro, String idemKeyHeader);

    /**
     * 缺货上报（拣货中 → 缺货待处理，差额释放预占）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo shortage(String doNo, ShortageOutOrderRo ro, String idemKeyHeader);

    /**
     * 缺货恢复拣货（缺货待处理 → 拣货中，重新占用）。
     *
     * @param doNo          出库单号
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo resumePicking(String doNo, String idemKeyHeader);

    /**
     * 称重/量方（→ 已复核；偏差超阈拦停）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo measure(String doNo, MeasureOutOrderRo ro, String idemKeyHeader);

    /**
     * 打包 + 面单绑定（已复核 → 已打包）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo pack(String doNo, PackOutOrderRo ro, String idemKeyHeader);

    /**
     * 扫码交接给装卸工（已打包 → 待装车）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo handover(String doNo, HandoverOutOrderRo ro, String idemKeyHeader);

    /**
     * 装车确认（待装车 → 已出库，**库存出账**）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo load(String doNo, LoadOutOrderRo ro, String idemKeyHeader);

    /**
     * 取消（按 {@code 01} §2.6 矩阵回滚）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo cancel(String doNo, CancelOutOrderRo ro, String idemKeyHeader);

    /**
     * 异常拦截（→ 已拦截，释放冻结、回滚预占）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 出库单详情
     */
    OutOrderDetailVo hold(String doNo, HoldOutOrderRo ro, String idemKeyHeader);

    /**
     * 部分发货（余量生成新单，原单转已出库）。
     *
     * @param doNo          出库单号
     * @param ro            入参
     * @param idemKeyHeader 幂等键请求头值
     * @return 拆单结果（原单 + 余量新单）
     */
    PartialShipVo partialShip(String doNo, PartialShipOutOrderRo ro, String idemKeyHeader);

    /**
     * 分页查询（API-OUT-13）。
     *
     * @param ro 查询条件
     * @return 分页结果
     */
    OutOrderPageVo page(PageOutOrderRo ro);

    /**
     * 详情（API-OUT-13）。
     *
     * @param doNo 出库单号
     * @return 出库单详情
     */
    OutOrderDetailVo detail(String doNo);

    /**
     * 状态轨迹（API-OUT-13）。
     *
     * @param doNo 出库单号
     * @return 轨迹列表
     */
    List<OutStatusLogVo> statusLogs(String doNo);
}
