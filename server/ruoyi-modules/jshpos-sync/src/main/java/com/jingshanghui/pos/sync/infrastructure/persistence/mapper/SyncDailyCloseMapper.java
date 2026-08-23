package com.jingshanghui.pos.sync.infrastructure.persistence.mapper;

import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort.DailySyncFacts;
import org.apache.ibatis.annotations.Param;

/** Sync Owner 的门店积压 XML 汇总。 */
public interface SyncDailyCloseMapper {
    DailySyncFacts aggregate(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
}
