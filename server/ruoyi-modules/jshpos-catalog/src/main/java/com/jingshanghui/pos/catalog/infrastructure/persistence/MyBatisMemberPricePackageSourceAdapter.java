package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.jingshanghui.pos.catalog.application.port.MemberPricePackageSourcePort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.MemberPricePersistenceMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 会员价数据包只读端口；不允许调用方以参数切换租户。 */
@Repository
@RequiredArgsConstructor
public class MyBatisMemberPricePackageSourceAdapter implements MemberPricePackageSourcePort {
    private final MemberPricePersistenceMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    public List<MemberPricePackageRow> listForPackage(String tenantId, Long storeId,
                                                       LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (!tenantContext.requireTenantId().equals(tenantId)) {
            throw new ServiceException("PRC-MEMBER-PKG-001: 会员价包租户上下文不一致", 403);
        }
        return List.copyOf(mapper.listForPackage(tenantId, storeId, windowStart, windowEnd));
    }
}
