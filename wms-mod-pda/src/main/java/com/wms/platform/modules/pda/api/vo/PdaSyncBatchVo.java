package com.wms.platform.modules.pda.api.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 批量补传结果（03 §七）。
 *
 * <p><b>整批永远返回 success（code=0）</b>：03 明确"逐条结果，不因单条失败而整批失败"。
 * 若整批失败，PDA 无法知道哪条成功了，只能全部重传，进而放大幂等压力与弱网代价。
 */
@Getter
@Setter
public class PdaSyncBatchVo {

    /** 批次序号（回显，PDA 据此清理本地批次）。 */
    private int batchSeq;

    /** 本批总条数。 */
    private int total;

    /** 成功受理条数。 */
    private int acceptedCount;

    /** 幂等重复条数。 */
    private int duplicatedCount;

    /** 冲突条数（转人工处理）。 */
    private int conflictCount;

    /** 拒绝条数（需重扫）。 */
    private int rejectedCount;

    /** 逐条结果。 */
    private List<PdaSyncResultVo> results;

    /**
     * 静态工厂（B4）。
     *
     * @param batchSeq 批次序号
     * @param results  逐条结果
     * @return 批量结果（计数由 results 汇总，避免两处口径不一致）
     */
    public static PdaSyncBatchVo of(int batchSeq, List<PdaSyncResultVo> results) {
        PdaSyncBatchVo vo = new PdaSyncBatchVo();
        vo.setBatchSeq(batchSeq);
        vo.setResults(results);
        vo.setTotal(results.size());
        vo.setAcceptedCount((int) count(results, "accepted"));
        vo.setDuplicatedCount((int) count(results, "duplicated"));
        vo.setConflictCount((int) count(results, "conflict"));
        vo.setRejectedCount((int) count(results, "rejected"));
        return vo;
    }

    /**
     * 统计某结果的条数。
     *
     * @param results 逐条结果
     * @param status  结果码
     * @return 条数
     */
    private static long count(List<PdaSyncResultVo> results, String status) {
        return results.stream().filter(r -> status.equals(r.getStatus())).count();
    }
}
