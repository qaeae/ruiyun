package com.wms.platform.modules.md.api.vo;

/**
 * 库区视图（03 API-MD-02/07）。
 *
 * @param id          库区 ID
 * @param warehouseId 所属仓库
 * @param zoneCode    库区编码
 * @param zoneType    类型
 * @param name        名称
 * @param isolation   是否隔离区
 */
public record ZoneVo(Long id, Long warehouseId, String zoneCode, String zoneType, String name, boolean isolation) {
}
