package com.wms.platform.modules.outbound.api.ro;

import jakarta.validation.constraints.Size;

/**
 * 装车确认（出库确认，{@code 03} API-OUT-09，状态机第 9 行）。
 *
 * <p><b>这是全链路最重的一步</b>：库存在此出账（SKU 实物 −q、库位实物 −q），且过了这一步就
 * **不可取消**（{@code 01} §2.1 不可逆点）。因此前置校验最严：
 * 已完成称重、已打包、已扫码交接、件数一致。
 *
 * <p>车辆入园登记与出园放行（{@code 01} §2.1 第 10 行）属门岗域（M10，本轮未实现），
 * 故本载荷不含车辆信息；门岗落地后在装车前置里补一条"车辆已入园"校验即可（接口不变）。
 *
 * @param remark 装车说明（可空，如车号说明）
 */
public record LoadOutOrderRo(@Size(max = 255, message = "装车说明不得超过 255 字符") String remark) {
}
