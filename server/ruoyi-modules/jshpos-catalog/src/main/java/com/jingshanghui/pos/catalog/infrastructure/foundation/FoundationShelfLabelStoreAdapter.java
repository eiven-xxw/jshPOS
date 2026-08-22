package com.jingshanghui.pos.catalog.infrastructure.foundation;

import com.jingshanghui.pos.catalog.application.port.ShelfLabelStorePort;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 通过 Foundation 应用服务读取可信门店目录，禁止 ShelfLabel 直接查询门店表。 */
@Component
@RequiredArgsConstructor
public class FoundationShelfLabelStoreAdapter implements ShelfLabelStorePort {

    private final StoreService storeService;
    private final ScopeAuthorizationService authorizationService;

    @Override
    public List<StoreSnapshot> listAccessibleActiveStores() {
        return storeService.list().stream()
            .filter(store -> "ACTIVE".equals(store.status()))
            .map(this::snapshot)
            .toList();
    }

    @Override
    public StoreSnapshot requireAccessibleStore(Long storeId) {
        authorizationService.requireStoreAccess(storeId);
        return storeService.list().stream()
            .filter(store -> storeId.equals(store.storeId()) && "ACTIVE".equals(store.status()))
            .findFirst().map(this::snapshot)
            .orElseThrow(() -> new ServiceException("LBL-SCOPE-001: 门店不存在、不可用或超出数据范围", 404));
    }

    private StoreSnapshot snapshot(StoreView store) {
        return new StoreSnapshot(store.storeId(), store.code(), store.name());
    }
}
