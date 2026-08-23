package com.jingshanghui.pos.member.infrastructure.persistence;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.member.application.port.MemberBenefitPackageSourcePort;
import com.jingshanghui.pos.member.infrastructure.persistence.mapper.BenefitPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 会员权益数据包只读端口；调用方租户必须与可信上下文一致。 */
@Repository
@RequiredArgsConstructor
public class MyBatisMemberBenefitPackageSourceAdapter implements MemberBenefitPackageSourcePort {
    private final BenefitPersistenceMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    public List<BenefitPackageRow> listForPackage(String tenantId, Long storeId,
                                                   LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (!tenantContext.requireTenantId().equals(tenantId)) {
            throw new ServiceException("MBR-PKG-001: 权益包租户上下文不一致", 403);
        }
        return List.copyOf(mapper.listForPackage(tenantId, storeId, windowStart, windowEnd));
    }
}
