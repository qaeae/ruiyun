package com.wms.platform.modules.md.api.vo;

/**
 * 新增成功返回的主键（主数据新增接口统一返回，避免让调用方从列表里"猜哪条是刚建的"）。
 *
 * @param id 主键
 */
public record IdVo(Long id) {
}
