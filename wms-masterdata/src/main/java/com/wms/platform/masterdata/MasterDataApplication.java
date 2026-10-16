package com.wms.platform.masterdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 主数据服务启动类（P1.2 从单体拆出的第二个服务）。
 *
 * <p><b>扫描范围</b>：{@code config}（共享装配）+ {@code common}（横切能力）+
 * {@code modules.md}（本域）+ {@code modules.au}。
 *
 * <p><b>P1.3 已收敛</b>：鉴权拦截器与权限码都已移到共享内核/公共层，因此本服务
 * **不再依赖 au 域**（只扫 config + common + md），服务边界到此干净。
 */
@SpringBootApplication(scanBasePackages = {
        "com.wms.platform.config",
        "com.wms.platform.common",
        "com.wms.platform.modules.md"
})
@EnableTransactionManagement
public class MasterDataApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MasterDataApplication.class, args);
    }
}
