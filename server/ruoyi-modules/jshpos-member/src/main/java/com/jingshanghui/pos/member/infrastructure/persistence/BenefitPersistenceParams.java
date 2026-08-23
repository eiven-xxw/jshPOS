package com.jingshanghui.pos.member.infrastructure.persistence;

import java.time.LocalDateTime;

/** 会员权益 XML Mapper 的显式多参数封装。 */
public final class BenefitPersistenceParams {
    public record ActiveLookup(String tenantId, Long storeId, String levelCode, LocalDateTime at) { }
    private BenefitPersistenceParams() { }
}
