package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort.ExchangeOrderSnapshot;
import org.apache.ibatis.annotations.Param;

/** 换货编排只读新销售头；复杂关联 SQL 固定在 XML 并显式携带可信租户。 */
public interface ExchangeOrderSnapshotMapper {
    ExchangeOrderSnapshot find(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
}
