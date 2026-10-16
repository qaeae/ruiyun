package com.wms.platform.modules.md.app;

import com.wms.platform.modules.md.api.ro.LocationCreateRo;
import com.wms.platform.modules.md.api.ro.MerchantCreateRo;
import com.wms.platform.modules.md.api.ro.SkuCreateRo;
import com.wms.platform.modules.md.api.ro.UnitConvCreateRo;
import com.wms.platform.modules.md.api.ro.WarehouseCreateRo;
import com.wms.platform.modules.md.api.ro.ZoneCreateRo;
import com.wms.platform.modules.md.api.vo.LocationVo;
import com.wms.platform.modules.md.api.vo.MerchantVo;
import com.wms.platform.modules.md.api.vo.SkuVo;
import com.wms.platform.modules.md.api.vo.UnitConvVo;
import com.wms.platform.modules.md.api.vo.WarehouseVo;
import com.wms.platform.modules.md.api.vo.ZoneVo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 主数据维护服务（03 API-MD-01 ~ API-MD-04、API-MD-07）。
 *
 * <p><b>为什么主数据要有独立的应用服务，而不是让各业务域自己 insert</b>：
 * 主数据的价值全在"唯一性与层级一致"上——商家编号不重复、库区必须属于该仓库、
 * 库位必须属于该库区、SKU 条码在商家内唯一。一旦允许业务域各自插入，
 * 这些约束就会退化成"研发记得就校验"，而漏掉的那条会在几个月后变成脏数据
 * （例如库位挂在别的仓库下 → 跨仓拣货，库存永远对不上）。
 *
 * <p>校验分层：**格式与必填由接口层 Ro 注解兜住；唯一性与层级一致性由本层（查库）兜住；
 * 最终一致性由数据库唯一键兜住**（三层缺一不可：只靠 Ro 挡不住重复提交，只靠唯一键报不出人话错误）。
 */
public interface MasterDataAdminService {

    /**
     * 新增商家（API-MD-01；联系人手机号服务端加密 + 脱敏）。
     *
     * @param ro 入参
     * @return 商家 ID
     */
    Long createMerchant(MerchantCreateRo ro);

    /**
     * 新增仓库（API-MD-02）。
     *
     * @param ro 入参
     * @return 仓库 ID
     */
    Long createWarehouse(WarehouseCreateRo ro);

    /**
     * 新增库区（API-MD-02；校验仓库存在）。
     *
     * @param ro 入参
     * @return 库区 ID
     */
    Long createZone(ZoneCreateRo ro);

    /**
     * 新增库位（API-MD-02；校验库区属于该仓库）。
     *
     * @param ro 入参
     * @return 库位 ID
     */
    Long createLocation(LocationCreateRo ro);

    /**
     * 新增 SKU（API-MD-03；含禁入品类标识）。
     *
     * @param ro 入参
     * @return SKU ID
     */
    Long createSku(SkuCreateRo ro);

    /**
     * 维护箱规换算（API-MD-04）。
     *
     * @param skuId 商品 ID
     * @param ro    入参
     * @return 换算配置 ID
     */
    Long addUnitConversion(long skuId, UnitConvCreateRo ro);

    /**
     * 数量单位换算（计费与库存上架共用）。
     *
     * @param skuId    商品 ID
     * @param qty      数量
     * @param fromUnit 源单位
     * @param toUnit   目标单位
     * @return 换算结果（6 位小数）
     */
    BigDecimal convertQty(long skuId, BigDecimal qty, String fromUnit, String toUnit);

    /**
     * 商家列表（API-MD-07）。
     *
     * @param status 状态（可空 = 不限）
     * @return 列表
     */
    List<MerchantVo> listMerchants(String status);

    /**
     * 仓库列表（API-MD-07）。
     *
     * @return 列表
     */
    List<WarehouseVo> listWarehouses();

    /**
     * 库区列表（API-MD-07）。
     *
     * @param warehouseId 仓库
     * @return 列表
     */
    List<ZoneVo> listZones(long warehouseId);

    /**
     * 库位列表（API-MD-07）。
     *
     * @param warehouseId 仓库
     * @return 列表
     */
    List<LocationVo> listLocations(long warehouseId);

    /**
     * SKU 列表（API-MD-07）。
     *
     * @param merchantId 商家
     * @return 列表
     */
    List<SkuVo> listSkus(long merchantId);

    /**
     * 箱规换算列表（API-MD-07）。
     *
     * @param skuId 商品 ID
     * @return 列表
     */
    List<UnitConvVo> listUnitConversions(long skuId);

    /**
     * 商家启停用（停用后不允许新建单据，历史数据保留）。
     *
     * @param merchantId 商家 ID
     * @param enabled    是否启用
     */
    void changeMerchantStatus(long merchantId, boolean enabled);

    /**
     * 仓库启停用。
     *
     * @param warehouseId 仓库 ID
     * @param enabled     是否启用
     */
    void changeWarehouseStatus(long warehouseId, boolean enabled);

    /**
     * 库位启停用（停用后 PDA 扫码会判定为不可用）。
     *
     * @param locationId 库位 ID
     * @param enabled    是否启用
     */
    void changeLocationStatus(long locationId, boolean enabled);

    /**
     * SKU 启停用（停用后扫码解析不出，等同不可用）。
     *
     * @param skuId   商品 ID
     * @param enabled 是否启用
     */
    void changeSkuStatus(long skuId, boolean enabled);
}
