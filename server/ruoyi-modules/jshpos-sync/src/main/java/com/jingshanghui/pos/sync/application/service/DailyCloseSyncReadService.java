package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncDailyCloseMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

/** 从 Sync Owner 读取门店同步健康事实，不暴露修复写入口。 */
@Service
@RequiredArgsConstructor
public class DailyCloseSyncReadService implements DailyCloseSyncReadPort {
    private final TrustedTenantContext tenantContext;
    private final SyncDailyCloseMapper mapper;

    @Override
    public DailySyncFacts read(Long storeId) {
        if (storeId == null || storeId <= 0) throw new ServiceException("OPS-SYNC-001: 门店无效", 400);
        return mapper.aggregate(tenantContext.requireTenantId(), storeId);
    }
}
