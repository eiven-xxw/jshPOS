package com.jingshanghui.pos.foundation.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * REST 层可见的 Gate 0 投影；不暴露 tenant_id 或持久化基类字段。
 */
public final class FoundationViews {

    private FoundationViews() {
    }

    public record OrgUnitView(Long orgUnitId, Long parentId, String code, String name, String type,
                              String status, Integer treeDepth, Integer version) {
    }

    public record StoreView(Long storeId, Long orgUnitId, Long platformDeptId, String code, String name,
                            String zoneId, LocalTime businessDayStart, String status, Integer version) {
    }

    public record BusinessDateView(Long storeId, String zoneId, LocalTime businessDayStart,
                                   Instant instant, LocalDate businessDate) {
    }

    public record StaffScopeView(Long staffScopeId, Long userId, String scopeType, Long orgUnitId,
                                 Long storeId, String status, Integer version) {
    }

    public record ConfigTemplateView(Long templateId, String code, String name, String industry,
                                     String status, Integer version) {
    }

    public record ConfigVersionView(Long configVersionId, Long templateId, Integer versionNo,
                                    String schemaVersion, String state, String contentSha256) {
    }

    public record ConfigBindingView(Long bindingId, Long templateId, String targetType, Long targetId,
                                    Long currentVersionId, Long previousVersionId, Integer version) {
    }

    public record AuditEventView(Long auditId, String correlationId, String action, String targetType,
                                 String targetId, String result, Instant occurredAt,
                                 String beforeSha256, String afterSha256, Map<String, Object> summary) {
    }
}
