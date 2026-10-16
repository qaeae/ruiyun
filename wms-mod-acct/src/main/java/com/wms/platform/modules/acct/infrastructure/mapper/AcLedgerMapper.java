package com.wms.platform.modules.acct.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wms.platform.modules.acct.infrastructure.po.AcLedgerPo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 资金流水 Mapper（表 {@code ac_ledger}）。
 *
 * <p>只暴露 {@link BaseMapper} 的追加能力：**流水不可删除**（B16），红冲是新增 {@code reverse_of_id}
 * 指向原流水的新记录，而不是修改原记录。因此这里刻意不提供任何批量更新/删除的便利方法。
 */
@Mapper
public interface AcLedgerMapper extends BaseMapper<AcLedgerPo> {
}
