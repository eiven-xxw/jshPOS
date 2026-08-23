package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.VersionView;

import java.time.LocalDateTime;
import java.util.List;

/** T2-MEM-003 Pricing Owner 会员价 XML 持久化端口。 */
public interface MemberPricePersistencePort {
    record VersionWrite(String tenantId,String versionId,String bookCode,int versionNo,Long storeId,
                        String contentSha256,Long createdBy,LocalDateTime createdAt) { }
    record ItemWrite(String tenantId,String itemId,String versionId,String levelCode,Long skuId,Long unitId,
                     long amountMinor,String contentSha256,LocalDateTime createdAt) { }
    record Transition(String tenantId,String versionId,String fromState,String toState,int expectedVersion,
                      Long approvedBy,LocalDateTime effectiveAt,LocalDateTime expiresAt,LocalDateTime changedAt) { }
    record StoredCommand(String requestSha256,String aggregateId,String resultSha256) { }
    record CommandWrite(String tenantId,String commandId,String commandType,String idempotencyKey,
                        String requestSha256,String aggregateId,String resultSha256,LocalDateTime createdAt) { }
    record OutboxWrite(String tenantId,String eventId,String eventType,String aggregateId,int aggregateVersion,
                       String payloadJson,String payloadSha256,LocalDateTime occurredAt) { }
    record CandidateLookup(String tenantId,String levelCode,Long skuId,Long unitId,Long storeId,
                           LocalDateTime at,String entitlementSnapshotId) { }
    int insertVersion(VersionWrite value);
    int insertItems(List<ItemWrite> values);
    VersionView findVersion(String tenantId,String versionId);
    VersionView lockVersion(String tenantId,String versionId);
    int transition(Transition value);
    int countPublishingConflicts(String tenantId,String versionId,Long storeId,LocalDateTime from,LocalDateTime to);
    MemberPriceCandidate findCandidate(CandidateLookup value);
    StoredCommand findCommand(String tenantId,String commandType,String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertOutbox(OutboxWrite value);
}
