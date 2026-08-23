package com.jingshanghui.pos.costing.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** Costing Owner 自有流水与余额序号差异窄查询。 */
public interface CostingExceptionMapper {
    List<CostingExceptionRow> listGaps(@Param("tenantId") String tenantId,@Param("storeId") Long storeId,@Param("limit") int limit);
    CostingExceptionRow find(@Param("tenantId") String tenantId,@Param("storeId") Long storeId,
                             @Param("dimensionKey") String dimensionKey);
    /**
     * 成本流水与余额序号缺口只读行。
     *
     * @param dimensionKey 仓库与 SKU 组成的成本维度
     * @param warehouseId 仓库业务标识
     * @param skuId 商品 SKU 平台主键
     * @param balanceSequence 成本余额已消费序号
     * @param ledgerSequence 成本流水最新序号
     * @param recordVersion 余额投影乐观锁版本
     * @param observedAt 最近投影更新时间
     */
    record CostingExceptionRow(String dimensionKey,String warehouseId,Long skuId,long balanceSequence,
                               long ledgerSequence,long recordVersion,LocalDateTime observedAt) { }
}
