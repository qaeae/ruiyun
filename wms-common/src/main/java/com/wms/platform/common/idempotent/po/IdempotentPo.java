package com.wms.platform.common.idempotent.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 幂等记录（表 {@code pf_idempotent}，见 {@code 01-数据模型DDL与状态机.md} §3.8）。
 *
 * <p>规范落地（B3/B4/B6）：
 * <ul>
 *   <li>后缀 {@code Po}、包位 {@code ..infrastructure|idempotent.po}，带 {@code @TableName(autoResultMap = true)}；</li>
 *   <li>逻辑删除列 {@code deleted} 显式标注 {@link TableLogic}；</li>
 *   <li><b>禁止 {@code new IdempotentPo()} + 连续 setter</b>，统一走静态工厂 {@link #of}；</li>
 *   <li>与本源强绑定的状态取值定义在类内（{@code STATUS_*}），不外提成常量类。</li>
 * </ul>
 *
 * <p>唯一键 {@code uk_idem(tenant_id, idem_key)} 是幂等的**最后防线**：
 * 即便应用层判重失效（多实例并发），数据库层也会拒绝第二条插入。
 */
@Getter
@Setter
@TableName(value = "pf_idempotent", autoResultMap = true)
public class IdempotentPo {

    /** 处理中：已占位，业务未结束。 */
    public static final String STATUS_PROCESSING = "处理中";

    /** 成功：响应已缓存，重放直接返回。 */
    public static final String STATUS_SUCCESS = "成功";

    /** 失败：允许调用方复用同一幂等键重试。 */
    public static final String STATUS_FAILED = "失败";

    /** 主键：雪花 ID 由应用生成（{@code 01} §1.1「不用自增」）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户 ID：一期固定 1，唯一索引首列。 */
    private Long tenantId;

    /** 幂等键：调用方传入的 {@code Idempotency-Key}，长度 ≤96。 */
    private String idemKey;

    /** 业务类型：如 {@code OUT_ORDER_CREATE}，用于排障与统计。 */
    private String bizType;

    /** 请求体规范化哈希（SHA-256）：判定"同键不同体"。 */
    private String requestHash;

    /** 首次成功响应缓存（JSON），重放时原样返回。 */
    private String responseJson;

    /** 状态：处理中 / 成功 / 失败。 */
    private String status;

    /** 过期时间：TTL 7 天，由定时任务清理。 */
    private LocalDateTime expireAt;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 创建人。 */
    private Long createdBy;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 更新人。 */
    private Long updatedBy;

    /** 逻辑删除标记：0 未删、1 已删（{@code 01} §1.1）。 */
    @TableLogic
    private Integer deleted;

    /**
     * 静态工厂：创建"处理中"占位记录（B4：禁止 new + 连续 setter）。
     *
     * @param id          雪花 ID
     * @param tenantId    租户 ID
     * @param idemKey     幂等键
     * @param bizType     业务类型
     * @param requestHash 请求体规范化哈希
     * @param expireAt    过期时间
     * @param createdBy   创建人
     * @return 处理中状态的幂等记录
     */
    public static IdempotentPo of(long id, long tenantId, String idemKey, String bizType,
                                  String requestHash, LocalDateTime expireAt, Long createdBy) {
        IdempotentPo po = new IdempotentPo();
        po.setId(id);
        po.setTenantId(tenantId);
        po.setIdemKey(idemKey);
        po.setBizType(bizType);
        po.setRequestHash(requestHash);
        po.setStatus(STATUS_PROCESSING);
        po.setExpireAt(expireAt);
        po.setCreatedBy(createdBy);
        po.setDeleted(0);
        return po;
    }
}
