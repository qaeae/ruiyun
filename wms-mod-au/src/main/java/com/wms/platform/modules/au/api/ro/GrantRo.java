package com.wms.platform.modules.au.api.ro;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量授权入参（角色授权限 / 用户授角色 / 用户授仓库共用形态说明见各类接口）。
 *
 * <p>**空列表是有意义的输入**：表示"撤销全部授权"。因此不能把"空集合"当成"没传"而跳过更新
 * （那会让撤销操作静默失效——用户以为撤了，实际还有权限）。
 *
 * @param codes      字符串编码集合（权限码或角色码）
 * @param warehouseIds 仓库 ID 集合
 */
public record GrantRo(

        /**
         * 字符串编码集合（权限码/角色码）。
         *
         * <p><b>必须有 @NotNull（真实缺陷）</b>：本接口是"整批替换"语义，空数组=撤销全部。
         * 若字段**漏传**被判成 null → 空数组 → **静默撤销该主体的全部授权**，而接口还返回成功。
         * 踩坑记录：用错字段名（roleCodes/permissionCodes）调本接口，得到 code=0 但库里一行没写。
         * 加 @NotNull 后：漏传 → 400；**显式传空数组 → 仍然是合法的"撤销全部"**（语义保留）。
         */
        @jakarta.validation.constraints.NotNull(message = "编码集合不能为空（撤销全部请显式传空数组）")
        List<String> codes,

        /** 仓库 ID 集合。 */
        @NotNull(message = "仓库集合不能为空（撤销全部请传空数组）")
        List<Long> warehouseIds) {
}
