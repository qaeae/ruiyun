package com.wms.platform.modules.outbound.config;

import com.wms.platform.modules.acct.app.FeeFreezeService;
import com.wms.platform.modules.logistics.app.ShipmentService;
import com.wms.platform.modules.outbound.app.remote.FeeFreezePort;
import com.wms.platform.modules.outbound.app.remote.ShipmentPort;
import com.wms.platform.modules.outbound.infrastructure.remote.AcctFeeFreezeAdapter;
import com.wms.platform.modules.outbound.infrastructure.remote.LogisticsShipmentAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 出库域装配：把两个跨域端口绑定到本地适配实现。
 *
 * <p><b>为什么用显式 {@code @Bean} 而不是给适配类加 {@code @Component}</b>：
 * 出库对外的越界依赖一共只有两处（资金冻结、装运单），集中在这里登记之后，
 * "本域有哪些跨域调用"变成一个可评审的清单；换实现（本地 → HTTP 远程）也只改这一个文件。
 * 反过来，散在适配类上的 {@code @Component} 会让"某个域悄悄多了一个跨域依赖"很难被发现。
 */
@Configuration
public class OutboundConfig {

    /**
     * 费用冻结端口（资金域）。
     *
     * @param feeFreezeService 资金域费用冻结服务（同进程 Bean）
     * @return 端口实现
     */
    @Bean
    public FeeFreezePort feeFreezePort(FeeFreezeService feeFreezeService) {
        return new AcctFeeFreezeAdapter(feeFreezeService);
    }

    /**
     * 装运单端口（物流域）。
     *
     * @param shipmentService 物流域装运单服务（同进程 Bean）
     * @return 端口实现
     */
    @Bean
    public ShipmentPort shipmentPort(ShipmentService shipmentService) {
        return new LogisticsShipmentAdapter(shipmentService);
    }
}
