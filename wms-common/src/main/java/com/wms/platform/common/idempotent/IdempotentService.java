package com.wms.platform.common.idempotent;

import java.util.Optional;

/**
 * 幂等裁决服务：所有写接口在**执行业务前**调用 {@link #begin}，成功后调用 {@link #complete}。
 *
 * <p>典型用法（业务代码不感知数据库细节）：
 * <pre>{@code
 * @IdempotentKey("OUT_ORDER_CREATE")
 * public ApiResponse<CreateOrderVO> create(String idemKey, CreateOrderCmd cmd) {
 *     String hash = RequestHasher.hash(cmd);
 *     Decision decision = idempotentService.begin(idemKey, "OUT_ORDER_CREATE", hash);
 *     if (decision.isReplay()) {
 *         return ApiResponse.ok(readCached(decision.cachedResponse()));
 *     }
 *     CreateOrderVO vo = doCreate(cmd);          // 真正的业务
 *     idempotentService.complete(idemKey, toJson(vo));
 *     return ApiResponse.ok(vo);
 * }
 * }</pre>
 */
public interface IdempotentService {

    /** 裁决结果。 */
    enum Decision {
        /** 首次请求：继续执行业务。 */
        PROCEED,
        /** 重复请求且请求体一致：直接返回首次响应，**不再执行业务**。 */
        REPLAY,
        /** 同一幂等键但请求体不同：返回 409 + 10002。 */
        MISMATCH,
        /** 并发重入（上一次仍在处理中）：返回 429 + 10003，调用方可稍后重试。 */
        IN_PROGRESS;

        public boolean isReplay() {
            return this == REPLAY;
        }
    }

    /**
     * 开始一次幂等操作。
     *
     * @param idemKey     调用方传入的 {@code Idempotency-Key}
     * @param bizType     业务类型（写入 pf_idempotent.biz_type）
     * @param requestHash 请求体规范化哈希，见 {@link RequestHasher}
     */
    Decision begin(String idemKey, String bizType, String requestHash);

    /** 业务成功：缓存响应体，状态置「成功」。 */
    void complete(String idemKey, String responseJson);

    /** 业务失败：状态置「失败」（保留记录，便于排障；不建议删除）。 */
    void fail(String idemKey);

    /** 取回首次响应（仅 REPLAY 场景使用）。 */
    Optional<String> cachedResponse(String idemKey);
}
