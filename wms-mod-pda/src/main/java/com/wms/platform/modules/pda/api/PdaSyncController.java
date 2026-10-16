package com.wms.platform.modules.pda.api;

import com.wms.platform.common.security.PublicApi;
import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.constant.HeaderConst;
import com.wms.platform.modules.pda.api.ro.PdaSyncBatchRo;
import com.wms.platform.modules.pda.api.vo.PdaConflictVo;
import com.wms.platform.modules.pda.api.vo.PdaSyncBatchVo;
import com.wms.platform.modules.pda.app.PdaSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PDA 离线补传接口（03 §3.12）。
 *
 * <p>两个接口都在 `/api/v1/pda/**` 下，因此都受设备绑定拦截器保护（03 §1.1）：
 * 未登记/停用设备的补传请求会在进入本类之前就被拒（403 + 10006）。
 *
 * <p>额外做一次**请求体与请求头的一致性校验**：{@code Ro.deviceCode} 与头 {@code X-Device-Code}
 * 必须一致。若不一致，说明有客户端在伪造/复用别的设备编号，必须拒绝——
 * 否则审计里的 `device_code` 就失去意义（库存流水会记成另一台设备的作业）。
 */
@Validated
@PublicApi("设备绑定（X-Device-Code 查设备档案）+ 逐条幂等键认证，报文不含用户会话概念；03 §3.12 未定义登录")
@RestController
@RequestMapping("/api/v1/pda")
@RequiredArgsConstructor
public class PdaSyncController {

    /** 补传服务。 */
    private final PdaSyncService pdaSyncService;

    /**
     * 批量补传（API-PDA-01；单批 ≤200 条）。
     *
     * @param headerDeviceCode 由设备绑定拦截器写入的请求属性（{@code X-Device-Code}）
     * @param ro               批量补传入参
     * @return 逐条结果
     */
    @PostMapping("/sync/batch")
    public ApiResponse<PdaSyncBatchVo> syncBatch(
            @RequestAttribute(HeaderConst.DEVICE_CODE) String headerDeviceCode,
            @Valid @RequestBody PdaSyncBatchRo ro) {
        // 1. 请求体设备号必须与请求头一致（防伪造/复用他机编号）
        if (!headerDeviceCode.equals(ro.deviceCode())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "请求体 deviceCode 与请求头不一致（头=" + headerDeviceCode + "，体=" + ro.deviceCode() + "）");
        }
        // 2. 交给应用服务逐条裁决
        return ApiResponse.ok(pdaSyncService.syncBatch(ro));
    }

    /**
     * 待人工处理的冲突列表（API-PDA-03）。
     *
     * @param limit 最大条数（默认 200）
     * @return 冲突列表
     */
    @GetMapping("/conflicts")
    public ApiResponse<List<PdaConflictVo>> listConflicts(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(pdaSyncService.listConflicts(limit));
    }
}
