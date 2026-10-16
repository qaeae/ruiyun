package com.wms.platform.modules.md.api.vo;

/**
 * 库位视图（03 API-MD-02/07）。
 *
 * @param id           库位 ID
 * @param warehouseId  所属仓库
 * @param zoneId       所属库区
 * @param locationCode 库位编码
 * @param abcClass     ABC 分类
 * @param pickable     是否可拣
 * @param quotaUnit    折算托盘位
 * @param status       状态
 */
public record LocationVo(Long id, Long warehouseId, Long zoneId, String locationCode, String abcClass,
                         boolean pickable, Integer quotaUnit, String status) {
}
