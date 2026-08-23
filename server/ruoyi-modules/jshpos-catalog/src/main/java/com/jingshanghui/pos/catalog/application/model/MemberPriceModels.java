package com.jingshanghui.pos.catalog.application.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** T2-MEM-003 Pricing Owner 会员价命令、版本和候选；不包含 PII。 */
public final class MemberPriceModels {
    public record ItemDraft(String itemId, String levelCode, Long skuId, Long unitId, Long amountMinor) { }
    public record CreateVersion(String commandId, String versionId, String bookCode, int versionNo,
                                Long storeId, List<ItemDraft> items, String correlationId) { }
    public record VersionAction(String commandId, String versionId, String contentSha256,
                                Instant effectiveAt, Instant expiresAt, String correlationId) { }
    public record VersionView(String versionId, String bookCode, int versionNo, Long storeId, String state,
                              LocalDateTime effectiveAt, LocalDateTime expiresAt, String contentSha256,
                              Long createdBy, Long approvedBy, int version) { }
    public record MemberPriceCandidate(String versionId, String itemId, String entitlementSnapshotId,
                                       String levelCode, Long skuId, Long unitId, Long storeId,
                                       long amountMinor, String currency, String contentSha256,
                                       Instant effectiveAt, Instant expiresAt) { }
    private MemberPriceModels() { }
}
