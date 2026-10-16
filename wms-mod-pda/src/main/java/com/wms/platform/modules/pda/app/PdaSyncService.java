package com.wms.platform.modules.pda.app;

import com.wms.platform.modules.pda.api.ro.PdaSyncBatchRo;
import com.wms.platform.modules.pda.api.vo.PdaConflictVo;
import com.wms.platform.modules.pda.api.vo.PdaSyncBatchVo;

import java.util.List;

/**
 * PDA 离线补传应用服务（03 §3.12 API-PDA-01 / API-PDA-03）。
 */
public interface PdaSyncService {

    /**
     * 批量补传（逐条裁决，不因单条失败而整批失败）。
     *
     * @param ro 批量补传入参
     * @return 逐条结果与计数
     */
    PdaSyncBatchVo syncBatch(PdaSyncBatchRo ro);

    /**
     * 列出待人工处理的冲突（{@code GET /pda/conflicts}）。
     *
     * @param limit 最大条数
     * @return 冲突列表
     */
    List<PdaConflictVo> listConflicts(int limit);
}
