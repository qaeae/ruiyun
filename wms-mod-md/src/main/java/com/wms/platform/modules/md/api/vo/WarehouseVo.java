package com.wms.platform.modules.md.api.vo;

import java.time.LocalTime;

/**
 * 仓库视图（03 API-MD-02/07）。
 *
 * @param id            仓库 ID
 * @param warehouseCode 仓库编码
 * @param name          名称
 * @param province      省
 * @param city          市
 * @param address       地址
 * @param cutoffTime    截单时间
 * @param status        状态
 */
public record WarehouseVo(Long id, String warehouseCode, String name, String province, String city, String address,
                          LocalTime cutoffTime, String status) {
}
