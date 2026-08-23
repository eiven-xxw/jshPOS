package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;

import java.time.Instant;

/** Promotion Owner 仅通过该端口获取经 Member 快照校验的会员价候选。 */
public interface MemberPriceResolutionPort {
    MemberPriceCandidate resolve(String entitlementSnapshotId, Long skuId, Long unitId, Long storeId, Instant at);
}
