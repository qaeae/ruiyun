package com.wms.platform.modules.fns.app;

import com.wms.platform.modules.fns.api.ro.AdvanceRegisterRo;
import com.wms.platform.modules.fns.api.vo.AdvanceLimitVo;
import com.wms.platform.modules.fns.api.vo.AdvanceVo;

import java.util.List;

/**
 * 垫付台账服务（03 API-FNS-06）。
 *
 * <p><b>垫付是什么</b>：商家余额不足以支付本次实际运费时，平台先替它付给承运商，形成对商家的应收。
 * 本质是**平台在放账**，因此必须限额（口径见 {@code AdvancePaymentServiceImpl} 类注释）。
 *
 * <p>一期实现的是**台账 + 限额**（可独立验证业务规则）；与资金流水的联动（冻结/划拨/核销）
 * 要等持牌机构接入（K2）后由资金域完成——本服务的方法签名已按那个方向设计，
 * 届时在 {@code register} 里追加"发起实际划拨"即可，台账逻辑不变。
 */
public interface AdvancePaymentService {

    /**
     * 登记垫付（同运单幂等；超限则拦截并转人工）。
     *
     * @param ro 入参
     * @return 运单号（幂等键）
     */
    String register(AdvanceRegisterRo ro);

    /**
     * 限额预检（供下单流程在事前询问"该商家还能不能垫"）。
     *
     * @param merchantId 商家
     * @return 预检结果
     */
    AdvanceLimitVo checkLimit(long merchantId);

    /**
     * 核销（商家后续扣款成功后调用）。
     *
     * @param waybillNo 运单号
     */
    void clear(String waybillNo);

    /**
     * 垫付台账查询。
     *
     * @param merchantId 商家（可空）
     * @return 列表
     */
    List<AdvanceVo> list(Long merchantId);
}
