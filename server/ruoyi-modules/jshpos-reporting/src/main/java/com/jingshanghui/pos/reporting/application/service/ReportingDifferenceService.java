package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.DifferenceTransition;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.DifferenceView;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.DifferenceRow;
import com.jingshanghui.pos.reporting.domain.CanonicalReportHash;
import com.jingshanghui.pos.reporting.domain.ReportRules;
import com.jingshanghui.pos.reporting.domain.ReportStates;
import com.jingshanghui.pos.reporting.infrastructure.id.ReportingIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Reporting 差异只记录和编排修复状态，绝不回写来源业务事实。 */
@Service
@RequiredArgsConstructor
public class ReportingDifferenceService {
    private final ReportingPersistencePort persistence;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final ReportingIdGenerator idGenerator;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DifferenceView record(String differenceType, String sourceEventId, String canonicalDetail) {
        String tenantId = tenantContext.requireTenantId();
        Instant now = clock.instant();
        DifferenceRow row = new DifferenceRow(idGenerator.next(), ReportRules.requireCode(differenceType,
            "RPT-G5D-030"), sourceEventId, "OPEN", CanonicalReportHash.sha256(canonicalDetail), null, now, 0);
        persistence.insertDifference(tenantId, row);
        auditService.append("REPORT_DIFFERENCE_OPENED", "REPORT_DIFFERENCE", row.differenceId(), null, null,
            Map.of("type", row.differenceType(), "detailSha256", row.detailSha256()));
        return toView(row);
    }

    @Transactional(readOnly = true)
    public List<DifferenceView> list(int limit) {
        tenantContext.requirePrincipal();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return persistence.listDifferences(tenantContext.requireTenantId(), safeLimit);
    }

    @Transactional
    public DifferenceView transition(DifferenceTransition command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        ReportRules.requireUlid(command.differenceId(), "RPT-G5D-031");
        DifferenceView current = persistence.findDifference(principal.tenantId(), command.differenceId());
        if (current == null) {
            throw new ServiceException("RPT-G5D-032: 差异不存在或不可见", 404);
        }
        String to = ReportStates.transitionDifference(current.state(), command.toState());
        String reasonHash = CanonicalReportHash.sha256(command.reason() == null ? "" : command.reason());
        if (persistence.transitionDifference(principal.tenantId(), command.differenceId(), current.state(), to,
            principal.userId(), reasonHash, command.expectedVersion(), clock.instant()) != 1) {
            throw new ServiceException("RPT-G5D-033: 差异版本或状态冲突", 409);
        }
        DifferenceView changed = persistence.findDifference(principal.tenantId(), command.differenceId());
        auditService.append("REPORT_DIFFERENCE_TRANSITIONED", "REPORT_DIFFERENCE", command.differenceId(),
            Map.of("state", current.state()), Map.of("state", changed.state()),
            Map.of("reasonSha256", reasonHash, "correlationId", command.correlationId()));
        return changed;
    }

    private DifferenceView toView(DifferenceRow row) {
        return new DifferenceView(row.differenceId(), row.differenceType(), row.sourceEventId(), row.state(),
            row.detailSha256(), row.assignedTo(), row.detectedAt(), row.version());
    }
}
